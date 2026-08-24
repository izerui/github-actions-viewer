package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** A GitHub repository visible inside the current IDE workspace. */
data class WorkspaceRepository(
    val coordinates: RepoCoordinates,
    val root: Path,
    val branch: String?,
)

/** Reads all GitHub repositories that belong to the current workspace. */
class IdeGitRepoProvider(
    private val project: Project,
) {
    fun repositories(): List<WorkspaceRepository> {
        val registered =
            GitRepositoryManager.getInstance(project).repositories.mapNotNull { repository ->
                val coordinates =
                    repository.remotes
                        .sortedBy { if (it.name == "origin") 0 else 1 }
                        .asSequence()
                        .flatMap { it.urls.asSequence() }
                        .mapNotNull(GitRemoteParser::parse)
                        .firstOrNull()
                        ?: return@mapNotNull null
                WorkspaceRepository(coordinates, repository.root.toNioPath(), repository.currentBranchName)
            }

        val discovered =
            project.basePath
                ?.let(Path::of)
                ?.let(::discoverWorkspaceRepositories)
                .orEmpty()
        return (registered + discovered)
            .distinctBy { it.root.toAbsolutePath().normalize() }
            .sortedBy { it.coordinates.toString() }
    }
}

/** Discovers the workspace root and its direct child Git repositories without IDE dependencies. */
internal fun discoverWorkspaceRepositories(base: Path): List<WorkspaceRepository> =
    runCatching {
        val candidates =
            buildList {
                add(base)
                if (base.isDirectory()) {
                    Files.newDirectoryStream(base).use { entries ->
                        entries.filterTo(this) { it.isDirectory() }
                    }
                }
            }
        candidates.mapNotNull(::readWorkspaceRepository)
    }.getOrDefault(emptyList())

internal fun readWorkspaceRepository(root: Path): WorkspaceRepository? =
    runCatching {
        val gitDir = root.resolve(".git")
        if (!gitDir.isDirectory()) return null
        val config = gitDir.resolve("config")
        if (!Files.isRegularFile(config)) return null

        var currentRemote: String? = null
        val remoteUrls = mutableListOf<Pair<String, String>>()
        Files.readAllLines(config).forEach { rawLine ->
            val line = rawLine.trim()
            val section = REMOTE_SECTION.matchEntire(line)
            if (section != null) {
                currentRemote = section.groupValues[1]
            } else if (line.startsWith("[")) {
                currentRemote = null
            } else {
                val remote = currentRemote ?: return@forEach
                val separator = line.indexOf('=')
                if (separator > 0 && line.substring(0, separator).trim().equals("url", ignoreCase = true)) {
                    remoteUrls += remote to line.substring(separator + 1).trim()
                }
            }
        }

        val coordinates =
            remoteUrls
                .sortedBy { if (it.first == "origin") 0 else 1 }
                .firstNotNullOfOrNull { GitRemoteParser.parse(it.second) }
                ?: return null
        val head = Files.readString(gitDir.resolve("HEAD")).trim()
        val branch = head.removePrefix(HEAD_BRANCH_PREFIX).takeIf { head.startsWith(HEAD_BRANCH_PREFIX) }
        WorkspaceRepository(coordinates, root, branch)
    }.getOrNull()

private const val HEAD_BRANCH_PREFIX = "ref: refs/heads/"
private val REMOTE_SECTION = Regex("""\[remote\s+"([^"]+)"\]""", RegexOption.IGNORE_CASE)
