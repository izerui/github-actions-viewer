package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates

/**
 * 将 git remote URL 解析为仓库坐标。纯函数，无任何 IDE 依赖。
 * 仅接受 github.com，其余主机一律返回 null。
 */
object GitRemoteParser {

    private const val GITHUB_HOST = "github.com"

    // git@github.com:owner/repo(.git)
    private val SCP_STYLE = Regex("""^[^@]+@([^:]+):([^/]+)/(.+?)(?:\.git)?/?$""")

    // ssh://git@github.com(:22)/owner/repo(.git)
    private val SSH_URL = Regex("""^ssh://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/(.+?)(?:\.git)?/?$""")

    // https://(token@)github.com(:443)/owner/repo(.git)
    private val HTTP_URL = Regex("""^https?://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/(.+?)(?:\.git)?/?$""")

    fun parse(url: String): RepoCoordinates? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val match = SSH_URL.find(trimmed)
            ?: HTTP_URL.find(trimmed)
            ?: SCP_STYLE.find(trimmed)
            ?: return null

        val (host, owner, name) = match.destructured
        if (!host.equals(GITHUB_HOST, ignoreCase = true)) return null
        if (owner.isBlank() || name.isBlank()) return null
        if (name.contains('/')) return null

        return RepoCoordinates(owner, name)
    }
}
