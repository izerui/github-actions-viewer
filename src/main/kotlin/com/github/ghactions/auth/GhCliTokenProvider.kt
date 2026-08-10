package com.github.ghactions.auth

sealed interface TokenResult {
    data class Success(val token: String) : TokenResult
    data object GhNotInstalled : TokenResult
    data object GhNotLoggedIn : TokenResult
}

/**
 * 通过 `gh auth token` 获取 GitHub token。
 * token 只在内存中传递，禁止写入磁盘、日志或异常消息。
 */
class GhCliTokenProvider(private val runner: CommandRunner) {

    fun token(): TokenResult {
        val output = runner.run(listOf("gh", "auth", "token")) ?: return TokenResult.GhNotInstalled
        if (output.exitCode != 0) return TokenResult.GhNotLoggedIn
        val token = output.stdout.trim()
        return if (token.isEmpty()) TokenResult.GhNotLoggedIn else TokenResult.Success(token)
    }
}
