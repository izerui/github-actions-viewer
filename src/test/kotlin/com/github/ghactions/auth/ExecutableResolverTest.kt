package com.github.ghactions.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 可执行文件解析。
 *
 * 存在的理由：ProcessBuilder 查找可执行文件时用的是 **JVM 自身的 PATH**，
 * 不看 `builder.environment()` 里设置的值——后者只影响子进程运行起来之后看到的
 * 环境。从 Dock 启动的 IDE 其 PATH 只有 /usr/bin:/bin，于是裸命令名一律找不到。
 * 因此必须自己用完整 PATH 解析出绝对路径。
 */
class ExecutableResolverTest {

    private fun executableAt(vararg paths: String): (File) -> Boolean =
        { it.path in paths.toSet() }

    @Test
    fun `在 PATH 中逐段查找并返回绝对路径`() {
        val resolved = resolveExecutable(
            command = "gh",
            path = "/usr/bin:/opt/homebrew/bin:/bin",
            isExecutable = executableAt("/opt/homebrew/bin/gh"),
        )
        assertEquals("/opt/homebrew/bin/gh", resolved)
    }

    @Test
    fun `按 PATH 顺序取第一个命中的`() {
        val resolved = resolveExecutable(
            command = "gh",
            path = "/usr/local/bin:/opt/homebrew/bin",
            isExecutable = executableAt("/usr/local/bin/gh", "/opt/homebrew/bin/gh"),
        )
        assertEquals("/usr/local/bin/gh", resolved)
    }

    @Test
    fun `找不到时返回 null`() {
        assertNull(
            resolveExecutable("gh", "/usr/bin:/bin", executableAt("/opt/homebrew/bin/gh")),
        )
    }

    @Test
    fun `PATH 为空或缺失时返回 null`() {
        assertNull(resolveExecutable("gh", null, executableAt("/opt/homebrew/bin/gh")))
        assertNull(resolveExecutable("gh", "", executableAt("/opt/homebrew/bin/gh")))
    }

    @Test
    fun `已是绝对路径则原样返回`() {
        val resolved = resolveExecutable(
            command = "/custom/path/gh",
            path = "/usr/bin",
            isExecutable = executableAt("/custom/path/gh"),
        )
        assertEquals("/custom/path/gh", resolved)
    }

    @Test
    fun `忽略 PATH 中的空段`() {
        val resolved = resolveExecutable(
            command = "gh",
            path = "::/opt/homebrew/bin:",
            isExecutable = executableAt("/opt/homebrew/bin/gh"),
        )
        assertEquals("/opt/homebrew/bin/gh", resolved)
    }
}
