package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

sealed class GitResult<out T> {
    data class Success<T>(val data: T) : GitResult<T>()
    data class Error(val message: String) : GitResult<Nothing>()
}

@Service(Service.Level.PROJECT)
class GitService(private val project: Project) {

    private val logger = Logger.getInstance(GitService::class.java)

    private val repositoryManager: GitRepositoryManager?
        get() = GitRepositoryManager.getInstance(project)

    private val changeListManager: ChangeListManager
        get() = ChangeListManager.getInstance(project)

    fun getCurrentRepository(): GitRepository? {
        return repositoryManager?.repositories?.firstOrNull()
    }

    fun getCurrentBranch(): String? {
        return getCurrentRepository()?.currentBranch?.name
    }

    fun getBaseBranch(): String {
        // Try to determine the base branch
        val repository = getCurrentRepository() ?: return DEFAULT_BASE_BRANCH

        // Check for common base branches
        val branches = repository.branches.localBranches.map { it.name }

        return COMMON_BASE_BRANCHES.find { branches.contains(it) } ?: DEFAULT_BASE_BRANCH
    }

    fun getStagedFiles(): List<String> {
        val changes = changeListManager.defaultChangeList.changes
        return changes.filter { it.fileStatus.id != "UNVERSIONED" }
            .mapNotNull { change ->
                change.virtualFile?.path ?: change.afterRevision?.file?.path
            }
    }

    fun getStagedDiff(): GitResult<String> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.DIFF)
            handler.addParameters("--cached", "--no-color")

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                val diff = result.outputAsJoinedString
                if (diff.isBlank()) {
                    GitResult.Error("No staged changes found. Please stage your changes first.")
                } else {
                    GitResult.Success(diff)
                }
            } else {
                GitResult.Error("Failed to get diff: ${result.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to get staged diff", e)
            GitResult.Error("Failed to get staged diff: ${e.message}")
        }
    }

    fun getCommitsSinceBranch(baseBranch: String): GitResult<List<String>> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.LOG)
            handler.addParameters(
                "$baseBranch..HEAD",
                "--oneline",
                "--no-decorate",
                "--no-color"
            )

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                val commits = result.output
                    .filter { it.isNotBlank() }
                    .map { it.trim() }

                if (commits.isEmpty()) {
                    GitResult.Error("No commits found on the current branch since $baseBranch")
                } else {
                    GitResult.Success(commits)
                }
            } else {
                // Try without the base branch comparison (for new branches)
                GitResult.Success(emptyList())
            }
        } catch (e: Exception) {
            logger.error("Failed to get commits", e)
            GitResult.Error("Failed to get commit history: ${e.message}")
        }
    }

    fun getDiffSinceBranch(baseBranch: String): GitResult<String> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.DIFF)
            handler.addParameters("$baseBranch...HEAD", "--no-color", "--stat")

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                GitResult.Success(result.outputAsJoinedString)
            } else {
                GitResult.Success("")
            }
        } catch (e: Exception) {
            logger.error("Failed to get diff since branch", e)
            GitResult.Error("Failed to get diff: ${e.message}")
        }
    }

    fun commit(message: String): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.COMMIT)
            handler.addParameters("-m", message)

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                repository.update()
                GitResult.Success(Unit)
            } else {
                GitResult.Error("Commit failed: ${result.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to commit", e)
            GitResult.Error("Failed to commit: ${e.message}")
        }
    }

    companion object {
        private const val DEFAULT_BASE_BRANCH = "main"
        private val COMMON_BASE_BRANCHES = listOf("main", "master", "develop", "development")

        fun getInstance(project: Project): GitService {
            return project.getService(GitService::class.java)
        }
    }
}
