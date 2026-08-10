package com.github.ghactions.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GhCliTokenProviderTest {

    private fun provider(output: CommandOutput?): GhCliTokenProvider =
        GhCliTokenProvider(CommandRunner { output })

    @Test
    fun `正常输出返回 token`() {
        val result = provider(CommandOutput(0, "gho_abc123\n", "")).token()
        assertEquals(TokenResult.Success("gho_abc123"), result)
    }

    @Test
    fun `进程无法启动视为未安装`() {
        assertSame(TokenResult.GhNotInstalled, provider(null).token())
    }

    @Test
    fun `非零退出码视为未登录`() {
        val output = CommandOutput(1, "", "gh: To get started with GitHub CLI, please run: gh auth login")
        assertSame(TokenResult.GhNotLoggedIn, provider(output).token())
    }

    @Test
    fun `退出码为零但输出为空视为未登录`() {
        assertSame(TokenResult.GhNotLoggedIn, provider(CommandOutput(0, "   \n", "")).token())
    }

    @Test
    fun `合流输出含前导噪声行时取末行 token`() {
        // stderr 合流到 stdout 后，gh 的升级提示等噪声行出现在 token 之前，退出码仍为 0。
        val merged = "A new release of gh is available: 2.40.0\ngho_realtoken123\n"
        val result = provider(CommandOutput(0, merged, "")).token()
        assertEquals(TokenResult.Success("gho_realtoken123"), result)
    }

    @Test
    fun `调用的是 gh auth token`() {
        var captured: List<String>? = null
        val runner = CommandRunner { cmd ->
            captured = cmd
            CommandOutput(0, "t", "")
        }
        GhCliTokenProvider(runner).token()
        assertEquals(listOf("gh", "auth", "token"), captured)
    }
}
