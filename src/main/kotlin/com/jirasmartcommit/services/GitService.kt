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

    fun getLocalBranches(): List<String> {
        val repository = getCurrentRepository() ?: return emptyList()
        return repository.branches.localBranches.map { it.name }
    }

    fun getAvailableBaseBranches(): List<String> {
        val repository = getCurrentRepository() ?: return listOf("main")
        val localBranches = repository.branches.localBranches.map { it.name }

        // Prioritize common base branches, then add remaining branches
        val prioritized = COMMON_BASE_BRANCHES.filter { localBranches.contains(it) }
        val remaining = localBranches.filter { !prioritized.contains(it) }.sorted()

        return prioritized + remaining
    }

    fun branchExists(branchName: String): Boolean {
        val repository = getCurrentRepository() ?: return false
        return repository.branches.localBranches.any { it.name == branchName }
    }

    fun createAndCheckoutBranch(branchName: String, baseBranch: String): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            // First checkout the base branch
            val checkoutBaseHandler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
            checkoutBaseHandler.addParameters(baseBranch)

            val checkoutBaseResult = Git.getInstance().runCommand(checkoutBaseHandler)
            if (!checkoutBaseResult.success()) {
                return GitResult.Error("Failed to checkout base branch '$baseBranch': ${checkoutBaseResult.errorOutputAsJoinedString}")
            }

            // Create and checkout the new branch
            val createHandler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
            createHandler.addParameters("-b", branchName)

            val createResult = Git.getInstance().runCommand(createHandler)

            if (createResult.success()) {
                repository.update()
                GitResult.Success(Unit)
            } else {
                GitResult.Error("Failed to create branch: ${createResult.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to create and checkout branch", e)
            GitResult.Error("Failed to create branch: ${e.message}")
        }
    }

    fun checkoutBranch(branchName: String): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
            handler.addParameters(branchName)

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                repository.update()
                GitResult.Success(Unit)
            } else {
                GitResult.Error("Failed to checkout branch: ${result.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to checkout branch", e)
            GitResult.Error("Failed to checkout branch: ${e.message}")
        }
    }

    fun push(branchName: String? = null): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.PUSH)

            // If branch specified, push that branch; otherwise push current branch
            if (branchName != null) {
                handler.addParameters("origin", branchName)
            } else {
                // Push current branch with upstream tracking
                handler.addParameters("-u", "origin", "HEAD")
            }

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                repository.update()
                GitResult.Success(Unit)
            } else {
                val errorMsg = result.errorOutputAsJoinedString
                when {
                    errorMsg.contains("no upstream branch") || errorMsg.contains("has no upstream") ->
                        GitResult.Error("No upstream branch configured. The branch may need to be pushed with: git push -u origin $branchName")
                    errorMsg.contains("rejected") ->
                        GitResult.Error("Push rejected. The remote contains commits that you do not have locally. Pull first and try again.")
                    errorMsg.contains("Permission denied") || errorMsg.contains("authentication") ->
                        GitResult.Error("Push failed: Authentication error. Please check your Git credentials.")
                    else ->
                        GitResult.Error("Push failed: $errorMsg")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to push", e)
            GitResult.Error("Failed to push: ${e.message}")
        }
    }

    fun hasRemote(): Boolean {
        val repository = getCurrentRepository() ?: return false
        return repository.remotes.isNotEmpty()
    }

    fun getRemoteUrl(): String? {
        val repository = getCurrentRepository() ?: return null
        val remote = repository.remotes.firstOrNull() ?: return null
        return remote.firstUrl
    }

    data class PullRequestUrlResult(
        val url: String,
        val platform: GitPlatform,
        val requiresManualInput: Boolean = false
    )

    fun buildPullRequestUrl(
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String
    ): GitResult<PullRequestUrlResult> {
        val remoteUrl = getRemoteUrl()
            ?: return GitResult.Error("No remote URL found. Please add a remote to your repository.")

        val repoInfo = parseRemoteUrl(remoteUrl)
            ?: return GitResult.Error("Could not parse remote URL: $remoteUrl")

        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedDescription = java.net.URLEncoder.encode(description, "UTF-8")

        val (prUrl, requiresManualInput) = when (repoInfo.platform) {
            GitPlatform.GITHUB -> {
                "${repoInfo.baseUrl}/${repoInfo.owner}/${repoInfo.repo}/compare/${targetBranch}...${sourceBranch}?quick_pull=1&title=${encodedTitle}&body=${encodedDescription}" to false
            }
            GitPlatform.GITLAB -> {
                "${repoInfo.baseUrl}/${repoInfo.owner}/${repoInfo.repo}/-/merge_requests/new?merge_request[source_branch]=${sourceBranch}&merge_request[target_branch]=${targetBranch}&merge_request[title]=${encodedTitle}&merge_request[description]=${encodedDescription}" to false
            }
            GitPlatform.BITBUCKET -> {
                // Bitbucket doesn't support title/description URL params, user needs to paste manually
                "${repoInfo.baseUrl}/${repoInfo.owner}/${repoInfo.repo}/pull-requests/new?source=${sourceBranch}&dest=${repoInfo.owner}%2F${repoInfo.repo}%3A%3A${targetBranch}" to true
            }
            GitPlatform.UNKNOWN -> {
                return GitResult.Error("Unsupported git platform. Supported platforms: GitHub, GitLab, Bitbucket")
            }
        }

        return GitResult.Success(PullRequestUrlResult(prUrl, repoInfo.platform, requiresManualInput))
    }

    private fun parseRemoteUrl(remoteUrl: String): RepoInfo? {
        // Handle SSH URLs: git@github.com:owner/repo.git
        val sshPattern = Regex("""git@([^:]+):([^/]+)/(.+?)(?:\.git)?$""")
        val sshMatch = sshPattern.find(remoteUrl)
        if (sshMatch != null) {
            val (host, owner, repo) = sshMatch.destructured
            val platform = detectPlatform(host)
            val baseUrl = "https://$host"
            return RepoInfo(platform, baseUrl, owner, repo.removeSuffix(".git"))
        }

        // Handle HTTPS URLs: https://github.com/owner/repo.git
        val httpsPattern = Regex("""https?://([^/]+)/([^/]+)/(.+?)(?:\.git)?$""")
        val httpsMatch = httpsPattern.find(remoteUrl)
        if (httpsMatch != null) {
            val (host, owner, repo) = httpsMatch.destructured
            val platform = detectPlatform(host)
            val baseUrl = "https://$host"
            return RepoInfo(platform, baseUrl, owner, repo.removeSuffix(".git"))
        }

        return null
    }

    private fun detectPlatform(host: String): GitPlatform {
        return when {
            host.contains("github") -> GitPlatform.GITHUB
            host.contains("gitlab") -> GitPlatform.GITLAB
            host.contains("bitbucket") -> GitPlatform.BITBUCKET
            else -> GitPlatform.UNKNOWN
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

enum class GitPlatform {
    GITHUB, GITLAB, BITBUCKET, UNKNOWN
}

data class RepoInfo(
    val platform: GitPlatform,
    val baseUrl: String,
    val owner: String,
    val repo: String
)
