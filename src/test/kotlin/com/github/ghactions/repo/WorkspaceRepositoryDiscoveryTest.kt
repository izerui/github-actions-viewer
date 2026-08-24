package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceRepositoryDiscoveryTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `发现工作区根目录和直接子目录中的 GitHub 仓库`() {
        createRepository(workspace, "https://github.com/root/project.git", "main")
        createRepository(workspace.resolve("maas-api"), "https://github.com/modexai/maas-api.git", "feature/multi-repo")
        createRepository(workspace.resolve("gitlab-project"), "https://gitlab.com/acme/ignored.git", "main")
        createRepository(
            workspace.resolve("nested").resolve("too-deep"),
            "https://github.com/acme/too-deep.git",
            "main",
        )

        val repositories = discoverWorkspaceRepositories(workspace)

        assertEquals(
            setOf(RepoCoordinates("root", "project"), RepoCoordinates("modexai", "maas-api")),
            repositories.map { it.coordinates }.toSet(),
        )
        assertEquals(
            "feature/multi-repo",
            repositories.single { it.coordinates.name == "maas-api" }.branch,
        )
    }

    @Test
    fun `优先 origin remote 且 detached HEAD 不冒充分支`() {
        val root = workspace.resolve("detached")
        val git = Files.createDirectories(root.resolve(".git"))
        Files.writeString(
            git.resolve("config"),
            """
            [remote "upstream"]
                url = https://github.com/upstream/project.git
            [remote "origin"]
                url = git@github.com:owner/project.git
            """.trimIndent(),
        )
        Files.writeString(git.resolve("HEAD"), "0123456789abcdef0123456789abcdef0123456789")

        val repository = readWorkspaceRepository(root)

        assertEquals(RepoCoordinates("owner", "project"), repository?.coordinates)
        assertNull(repository?.branch)
    }

    private fun createRepository(
        root: Path,
        remote: String,
        branch: String,
    ) {
        val git = Files.createDirectories(root.resolve(".git"))
        Files.writeString(
            git.resolve("config"),
            """
            [core]
                repositoryformatversion = 0
            [remote "origin"]
                url = $remote
            """.trimIndent(),
        )
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/$branch")
    }
}
