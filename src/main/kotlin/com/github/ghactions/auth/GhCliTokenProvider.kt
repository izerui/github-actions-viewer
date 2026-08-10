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
        // stderr 已合流进 stdout，gh 的升级提示等噪声行可能出现在 token 之前。
        // gh auth token 把 token 作为最后一行输出，故取最后一个非空行即可，不做前缀强校验
        // （GitHub 的 token 形状会变）。
        val token = output.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            .orEmpty()
        return if (token.isEmpty()) TokenResult.GhNotLoggedIn else TokenResult.Success(token)
    }
}
