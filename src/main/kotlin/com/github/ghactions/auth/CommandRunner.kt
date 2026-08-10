package com.github.ghactions.auth

import com.intellij.util.EnvironmentUtil
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * 在 [path] 中查找 [command] 并返回绝对路径；找不到返回 null。
 *
 * 必须自己解析，不能依赖 ProcessBuilder：它查找可执行文件时用的是 **JVM 自身的
 * PATH**，而不是 `builder.environment()` 里设置的值——后者只影响子进程运行起来
 * 之后看到的环境。从 Dock 启动的 IDE 其 PATH 通常只有 /usr/bin:/bin，
 * 于是 gh 这类装在 /opt/homebrew/bin 下的命令一律「找不到」。
 *
 * [isExecutable] 作为参数注入，便于测试时不触碰真实文件系统。
 */
internal fun resolveExecutable(
    command: String,
    path: String?,
    isExecutable: (File) -> Boolean = { it.canExecute() },
): String? {
    if (command.contains('/')) return command.takeIf { isExecutable(File(it)) }
    if (path.isNullOrEmpty()) return null
    return path.split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotEmpty() }
        .map { File(it, command) }
        .firstOrNull { isExecutable(it) }
        ?.path
}

/** 进程执行抽象。返回 null 表示进程根本无法启动（可执行文件不存在）。 */
fun interface CommandRunner {
    fun run(command: List<String>): CommandOutput?
}

/**
 * 真实进程执行器。
 *
 * 必须使用 EnvironmentUtil.getEnvironmentMap()：从 Dock 启动的 IDEA
 * 其自身 PATH 不含 /opt/homebrew/bin 等目录，直接执行会找不到 gh。
 * IDEA 启动时会加载用户登录 shell 的完整环境并由该方法提供。
 *
 * 采用「边读边等」：后台线程持续读干合流输出，主线程用 onExit().get(timeout) 限时等待。
 * 因此不受管道缓冲区大小限制，大输出（远超约 64KB）也不会因子进程写阻塞而被误判为超时。
 */
class ProcessCommandRunner(
    private val timeoutSeconds: Long = 10,
) : CommandRunner {

    override fun run(command: List<String>): CommandOutput? = try {
        // 用 IDE 加载的登录 shell 环境解析出绝对路径。EnvironmentUtil 提供的是完整
        // PATH（IDE 启动时从登录 shell 抓取），但仅靠它设置 environment 不足以让
        // ProcessBuilder 找到命令，必须由我们解析后传绝对路径。
        val env = EnvironmentUtil.getEnvironmentMap()
        val exe = resolveExecutable(command.first(), env["PATH"])
            ?: return null
        val builder = ProcessBuilder(listOf(exe) + command.drop(1))
        builder.environment().putAll(env)
        // 合并 stderr 到 stdout：单流读取消除「stderr 管道被填满而无人读」的经典死锁，
        // 也让后续单次 readText() 不会因为漏读某个流而卡住。
        builder.redirectErrorStream(true)
        val process = builder.start()

        // 边读边等：另起线程持续 readText() 读干管道，主线程用 onExit().get(timeout) 限时等待退出。
        // 这样既不会因为「先 waitFor 再读」而在大输出（超过管道缓冲区约 64KB）时死锁——
        // 子进程写满管道后阻塞、runner 白等到超时——也不会陷入永不返回的阻塞读：
        // 一旦超时，强杀进程会关闭管道让读线程见到 EOF 退出。
        val readerThread = ReaderThread(process)
        readerThread.start()
        val exited = try {
            process.onExit().get(timeoutSeconds, TimeUnit.SECONDS)
            true
        } catch (e: TimeoutException) {
            // gh 挂起（网络握手、keyring 等待输入、大输出无人及时读等），到点强杀并放弃。
            process.destroyForcibly()
            readerThread.join(1000)
            false
        }
        if (exited) {
            readerThread.join()
            CommandOutput(process.exitValue(), readerThread.output, "")
        } else {
            null
        }
    } catch (e: Exception) {
        // 可执行文件不存在、权限不足、线程被中断等，一律按「无法启动」处理
        null
    }

    /** 后台读干合流输出的线程。强杀进程会关闭管道，readText() 见到 EOF 后线程自然结束。 */
    private class ReaderThread(private val process: Process) : Thread("gh-actions-cmd-reader") {
        @Volatile
        var output: String = ""
            private set

        init {
            isDaemon = true
        }

        override fun run() {
            output = try {
                process.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                ""
            }
        }
    }
}
