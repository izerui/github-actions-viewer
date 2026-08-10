package com.github.ghactions.auth

import com.intellij.util.EnvironmentUtil
import java.util.concurrent.TimeUnit

data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

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
 */
class ProcessCommandRunner(
    private val timeoutSeconds: Long = 10,
) : CommandRunner {

    override fun run(command: List<String>): CommandOutput? = try {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(EnvironmentUtil.getEnvironmentMap())
        // 合并 stderr 到 stdout：单流读取消除「stderr 管道被填满而无人读」的经典死锁，
        // 也让后续单次 readText() 不会因为漏读某个流而卡住。
        builder.redirectErrorStream(true)
        val process = builder.start()

        // 关键顺序：先等超时，再读输出。若 gh 挂起（网络握手、keyring 等待输入等），
        // waitFor 到点即返回 false，我们强杀进程并放弃，绝不会先陷入永不返回的阻塞读。
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            // 进程已退出，管道内数据有界，此处 readText() 立即读到 EOF 返回。
            val output = process.inputStream.bufferedReader().readText()
            CommandOutput(process.exitValue(), output, "")
        }
    } catch (e: Exception) {
        // 可执行文件不存在、权限不足、线程被中断等，一律按「无法启动」处理
        null
    }
}
