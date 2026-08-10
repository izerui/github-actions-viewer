package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * 从 IDE 的 Git 集成读取当前项目的仓库坐标与分支。
 * 仅做取值与委托，判断逻辑全在 GitRemoteParser 中。
 */
class IdeGitRepoProvider(private val project: Project) {

    fun currentRepo(): RepoCoordinates? {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val remote = repository.remotes.firstOrNull { it.name == "origin" }
            ?: repository.remotes.firstOrNull()
            ?: return null
        val url = remote.urls.firstOrNull() ?: return null
        return GitRemoteParser.parse(url)
    }

    fun currentBranch(): String? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName
}
