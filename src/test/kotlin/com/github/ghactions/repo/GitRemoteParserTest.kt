package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitRemoteParserTest {

    private val expected = RepoCoordinates("octocat", "hello-world")

    @Test
    fun `scp 风格 ssh 地址`() {
        assertEquals(expected, GitRemoteParser.parse("git@github.com:octocat/hello-world.git"))
        assertEquals(expected, GitRemoteParser.parse("git@github.com:octocat/hello-world"))
    }

    @Test
    fun `ssh 协议地址`() {
        assertEquals(expected, GitRemoteParser.parse("ssh://git@github.com/octocat/hello-world.git"))
        assertEquals(expected, GitRemoteParser.parse("ssh://git@github.com:22/octocat/hello-world.git"))
    }

    @Test
    fun `https 地址`() {
        assertEquals(expected, GitRemoteParser.parse("https://github.com/octocat/hello-world.git"))
        assertEquals(expected, GitRemoteParser.parse("https://github.com/octocat/hello-world"))
        assertEquals(expected, GitRemoteParser.parse("https://github.com/octocat/hello-world/"))
    }

    @Test
    fun `https 地址带用户名`() {
        assertEquals(expected, GitRemoteParser.parse("https://token@github.com/octocat/hello-world.git"))
    }

    @Test
    fun `首尾空白被忽略`() {
        assertEquals(expected, GitRemoteParser.parse("  https://github.com/octocat/hello-world.git  "))
    }

    @Test
    fun `非 github 主机返回 null`() {
        assertNull(GitRemoteParser.parse("https://gitlab.com/octocat/hello-world.git"))
        assertNull(GitRemoteParser.parse("git@bitbucket.org:octocat/hello-world.git"))
        assertNull(GitRemoteParser.parse("https://github.company.com/octocat/hello-world.git"))
    }

    @Test
    fun `无法识别的输入返回 null`() {
        assertNull(GitRemoteParser.parse(""))
        assertNull(GitRemoteParser.parse("   "))
        assertNull(GitRemoteParser.parse("not a url at all"))
        assertNull(GitRemoteParser.parse("https://github.com/onlyowner"))
    }

    @Test
    fun `仓库名中的点号被保留`() {
        assertEquals(
            RepoCoordinates("octocat", "hello.world"),
            GitRemoteParser.parse("https://github.com/octocat/hello.world.git"),
        )
    }
}
