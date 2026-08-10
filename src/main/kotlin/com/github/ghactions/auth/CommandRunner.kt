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
        val process = builder.start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            CommandOutput(process.exitValue(), stdout, stderr)
        }
    } catch (e: Exception) {
        // 可执行文件不存在、权限不足、线程被中断等，一律按「无法启动」处理
        null
    }
}
