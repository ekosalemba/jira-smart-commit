package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jirasmartcommit.settings.PluginSettings
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.nio.file.Files

sealed class GitResult<out T> {
    data class Success<T>(val data: T) : GitResult<T>()
    data class Error(val message: String) : GitResult<Nothing>()
}

enum class FileStatus {
    ADDED, MODIFIED, DELETED, RENAMED, COPIED, UNTRACKED
}

data class FileChange(
    val path: String,
    val fileName: String,
    val status: FileStatus,
    val isStaged: Boolean,
    val hasUnstagedChanges: Boolean = false
)

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

    fun getCurrentBranch(rootDir: VirtualFile): String? {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.REV_PARSE)
            handler.addParameters("--abbrev-ref", "HEAD")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                result.outputAsJoinedString.trim().takeIf { it.isNotBlank() && it != "HEAD" }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to read branch for ${rootDir.path}", e)
            null
        }
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
        return getStagedDiff(repository.root)
    }

    fun getStagedDiff(rootDir: VirtualFile): GitResult<String> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.DIFF)
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

    fun getStagedFiles(rootDir: VirtualFile): List<String> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.DIFF)
            handler.addParameters("--cached", "--name-only", "--no-color")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                result.output.filter { it.isNotBlank() }.map { it.trim() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to list staged files", e)
            emptyList()
        }
    }

    fun getCommitsSinceBranch(baseBranch: String): GitResult<List<String>> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")
        return getCommitsSinceBranch(repository.root, baseBranch)
    }

    fun getCommitsSinceBranch(rootDir: VirtualFile, baseBranch: String): GitResult<List<String>> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.LOG)
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
        return getDiffSinceBranch(repository.root, baseBranch)
    }

    fun getDiffSinceBranch(rootDir: VirtualFile, baseBranch: String): GitResult<String> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.DIFF)
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

    fun getStagedNameStatus(): GitResult<List<String>> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")
        return getStagedNameStatus(repository.root)
    }

    fun getStagedNameStatus(rootDir: VirtualFile): GitResult<List<String>> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.DIFF)
            handler.addParameters("--cached", "--name-status", "--no-color")

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                GitResult.Success(result.output.filter { it.isNotBlank() })
            } else {
                GitResult.Success(emptyList())
            }
        } catch (e: Exception) {
            logger.error("Failed to get staged name-status", e)
            GitResult.Error("Failed to get staged name-status: ${e.message}")
        }
    }

    fun getCommitHistoryForTicket(ticketKey: String, limit: Int = 10): List<String> {
        val repository = getCurrentRepository() ?: return emptyList()

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.LOG)
            handler.addParameters(
                "--all",
                "--grep=$ticketKey",
                "--format=%h|%s|%ar",
                "-n", limit.toString()
            )

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                result.output.filter { it.isNotBlank() }.map { it.trim() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Failed to get commit history for ticket", e)
            emptyList()
        }
    }

    fun commit(message: String): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")
        return commit(repository.root, message)
    }

    fun commit(rootDir: VirtualFile, message: String): GitResult<Unit> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.COMMIT)
            handler.addParameters("-m", message)

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                getCurrentRepository()?.update()
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
        return push(repository.root, branchName)
    }

    fun push(rootDir: VirtualFile, branchName: String? = null): GitResult<Unit> {
        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.PUSH)

            if (branchName != null) {
                handler.addParameters("-u", "origin", branchName)
            } else {
                handler.addParameters("-u", "origin", "HEAD")
            }

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                getCurrentRepository()?.update()
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

    fun getAllChangedFiles(): GitResult<List<FileChange>> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")
        return getAllChangedFiles(repository.root)
    }

    fun getAllChangedFiles(rootDir: VirtualFile): GitResult<List<FileChange>> {
        return try {
            // Get staged files with status
            val stagedHandler = GitLineHandler(project, rootDir, GitCommand.DIFF)
            stagedHandler.addParameters("--cached", "--name-status", "--no-color")
            val stagedResult = Git.getInstance().runCommand(stagedHandler)

            val stagedFiles = if (stagedResult.success()) {
                parseStatusOutput(stagedResult.output, isStaged = true)
            } else {
                emptyList()
            }

            // Get unstaged files with status (tracked files only)
            val unstagedHandler = GitLineHandler(project, rootDir, GitCommand.DIFF)
            unstagedHandler.addParameters("--name-status", "--no-color")
            val unstagedResult = Git.getInstance().runCommand(unstagedHandler)

            val unstagedFiles = if (unstagedResult.success()) {
                parseStatusOutput(unstagedResult.output, isStaged = false)
            } else {
                emptyList()
            }

            // Get untracked files
            val untrackedHandler = GitLineHandler(project, rootDir, GitCommand.LS_FILES)
            untrackedHandler.addParameters("--others", "--exclude-standard")
            val untrackedResult = Git.getInstance().runCommand(untrackedHandler)

            val untrackedFiles = if (untrackedResult.success()) {
                untrackedResult.output
                    .filter { it.isNotBlank() }
                    .map { path ->
                        FileChange(
                            path = path.trim(),
                            fileName = path.trim().substringAfterLast('/'),
                            status = FileStatus.UNTRACKED,
                            isStaged = false
                        )
                    }
            } else {
                emptyList()
            }

            // Combine all files, detecting partial staging
            val stagedPaths = stagedFiles.map { it.path }.toSet()
            val unstagedPaths = unstagedFiles.map { it.path }.toSet()

            // Files with both staged and unstaged changes (partial staging)
            val partiallyStaged = stagedFiles
                .filter { it.path in unstagedPaths }
                .map { it.copy(hasUnstagedChanges = true) }

            // Fully staged files (no additional unstaged changes)
            val fullyStaged = stagedFiles
                .filter { it.path !in unstagedPaths }

            // Unstaged only files (not in staged list)
            val unstagedOnly = unstagedFiles
                .filter { it.path !in stagedPaths }

            val allFiles = partiallyStaged + fullyStaged + unstagedOnly + untrackedFiles

            GitResult.Success(allFiles.sortedBy { it.path })
        } catch (e: Exception) {
            logger.error("Failed to get changed files", e)
            GitResult.Error("Failed to get changed files: ${e.message}")
        }
    }

    private fun parseStatusOutput(output: List<String>, isStaged: Boolean): List<FileChange> {
        return output
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split("\t", limit = 2)
                if (parts.size < 2) return@mapNotNull null

                val statusChar = parts[0].firstOrNull() ?: return@mapNotNull null
                val path = parts[1]

                val status = when (statusChar) {
                    'A' -> FileStatus.ADDED
                    'M' -> FileStatus.MODIFIED
                    'D' -> FileStatus.DELETED
                    'R' -> FileStatus.RENAMED
                    'C' -> FileStatus.COPIED
                    else -> FileStatus.MODIFIED
                }

                FileChange(
                    path = path,
                    fileName = path.substringAfterLast('/'),
                    status = status,
                    isStaged = isStaged
                )
            }
    }

    fun stageFiles(files: List<String>): GitResult<Unit> {
        if (files.isEmpty()) return GitResult.Success(Unit)
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")
        return stageFiles(repository.root, files)
    }

    fun stageFiles(rootDir: VirtualFile, files: List<String>): GitResult<Unit> {
        if (files.isEmpty()) return GitResult.Success(Unit)

        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.ADD)
            handler.addParameters("--")
            files.forEach { handler.addParameters(it) }

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                getCurrentRepository()?.update()
                GitResult.Success(Unit)
            } else {
                GitResult.Error("Failed to stage files: ${result.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to stage files", e)
            GitResult.Error("Failed to stage files: ${e.message}")
        }
    }

    fun unstageFiles(files: List<String>): GitResult<Unit> {
        if (files.isEmpty()) return GitResult.Success(Unit)
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")
        return unstageFiles(repository.root, files)
    }

    fun unstageFiles(rootDir: VirtualFile, files: List<String>): GitResult<Unit> {
        if (files.isEmpty()) return GitResult.Success(Unit)

        return try {
            val handler = GitLineHandler(project, rootDir, GitCommand.RESTORE)
            handler.addParameters("--staged", "--")
            files.forEach { handler.addParameters(it) }

            val result = Git.getInstance().runCommand(handler)

            if (result.success()) {
                getCurrentRepository()?.update()
                GitResult.Success(Unit)
            } else {
                GitResult.Error("Failed to unstage files: ${result.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to unstage files", e)
            GitResult.Error("Failed to unstage files: ${e.message}")
        }
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

    // Worktree operations

    fun fetchRemote(remote: String = "origin", branch: String? = null): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        return try {
            val handler = GitLineHandler(project, repository.root, GitCommand.FETCH)
            handler.addParameters(remote)
            if (branch != null) {
                handler.addParameters(branch)
            }
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                GitResult.Success(Unit)
            } else {
                GitResult.Error("Failed to fetch: ${result.errorOutputAsJoinedString}")
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch remote", e)
            GitResult.Error("Failed to fetch: ${e.message}")
        }
    }

    fun branchExistsAnywhere(branchName: String): Boolean {
        val repository = getCurrentRepository() ?: return false
        val local = repository.branches.localBranches.any { it.name == branchName }
        if (local) return true
        val remote = repository.branches.remoteBranches.any {
            it.name == branchName || it.name == "origin/$branchName" || it.nameForRemoteOperations == branchName
        }
        return remote
    }

    fun addWorktree(worktreePath: String, branchName: String, baseBranch: String, createBranch: Boolean): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        val targetFile = File(worktreePath)
        if (targetFile.exists() && (targetFile.listFiles()?.isNotEmpty() == true)) {
            return GitResult.Error("Target path is not empty: $worktreePath")
        }
        targetFile.parentFile?.mkdirs()

        // Disable repo hooks (husky, etc.) for this command only — a fresh worktree checkout
        // doesn't need pre-commit/post-checkout tooling, and a failing hook (e.g. husky with
        // no node_modules yet in the new worktree) would otherwise fail the whole command even
        // though the worktree/branch gets created anyway. Doesn't affect hooks for later commits.
        val cmd = mutableListOf("git", "-c", "core.hooksPath=$NO_HOOKS_DIR", "worktree", "add")
        if (createBranch) {
            cmd += listOf("-b", branchName, worktreePath, baseBranch)
        } else {
            cmd += listOf(worktreePath, branchName)
        }
        val outcome = runProcess(cmd, File(repository.root.path))
        return if (outcome.exitCode == 0) {
            symlinkConfiguredPaths(repository.root.path, worktreePath)
            LocalFileSystem.getInstance().refreshAndFindFileByPath(worktreePath)
            GitResult.Success(Unit)
        } else {
            GitResult.Error("Failed to add worktree: ${outcome.stderr.ifBlank { outcome.stdout }}")
        }
    }

    /**
     * Symlinks user-configured paths (relative to repo root) from the source repo
     * into the newly created worktree — for gitignored files like .env or local.properties
     * that are needed to run the project but aren't checked out by `git worktree add`.
     */
    private fun symlinkConfiguredPaths(sourceRoot: String, worktreePath: String) {
        val paths = PluginSettings.instance.worktreeSymlinkPathList()
        for (relativePath in paths) {
            try {
                val source = File(sourceRoot, relativePath)
                if (!source.exists()) {
                    logger.warn("Worktree symlink skipped, source not found: $relativePath")
                    continue
                }

                val target = File(worktreePath, relativePath)
                if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                    logger.warn("Worktree symlink skipped, target already exists: $relativePath")
                    continue
                }

                target.parentFile?.mkdirs()
                Files.createSymbolicLink(target.toPath(), source.toPath())
            } catch (e: Exception) {
                logger.warn("Failed to symlink '$relativePath' into worktree", e)
            }
        }
    }

    fun removeWorktree(worktreePath: String, force: Boolean = false): GitResult<Unit> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        val cmd = mutableListOf("git", "worktree", "remove")
        if (force) cmd += "--force"
        cmd += worktreePath
        val outcome = runProcess(cmd, File(repository.root.path))
        return if (outcome.exitCode == 0) {
            GitResult.Success(Unit)
        } else {
            GitResult.Error("Failed to remove worktree: ${outcome.stderr.ifBlank { outcome.stdout }}")
        }
    }

    data class WorktreeInfo(val path: String, val branch: String?, val head: String?)

    fun listWorktrees(): GitResult<List<WorktreeInfo>> {
        val repository = getCurrentRepository()
            ?: return GitResult.Error("No Git repository found in the current project")

        val outcome = runProcess(listOf("git", "worktree", "list", "--porcelain"), File(repository.root.path))
        if (outcome.exitCode != 0) {
            return GitResult.Error("Failed to list worktrees: ${outcome.stderr.ifBlank { outcome.stdout }}")
        }

        val infos = mutableListOf<WorktreeInfo>()
        var currentPath: String? = null
        var currentHead: String? = null
        var currentBranch: String? = null

        for (line in outcome.stdout.lines()) {
            when {
                line.startsWith("worktree ") -> {
                    if (currentPath != null) {
                        infos.add(WorktreeInfo(currentPath, currentBranch, currentHead))
                    }
                    currentPath = line.removePrefix("worktree ").trim()
                    currentHead = null
                    currentBranch = null
                }
                line.startsWith("HEAD ") -> currentHead = line.removePrefix("HEAD ").trim()
                line.startsWith("branch ") -> currentBranch = line.removePrefix("branch ").trim().removePrefix("refs/heads/")
            }
        }
        if (currentPath != null) {
            infos.add(WorktreeInfo(currentPath, currentBranch, currentHead))
        }

        return GitResult.Success(infos)
    }

    private data class ProcessOutcome(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runProcess(command: List<String>, workingDir: File): ProcessOutcome {
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            ProcessOutcome(exit, stdout, stderr)
        } catch (e: Exception) {
            logger.error("Failed to run command: ${command.joinToString(" ")}", e)
            ProcessOutcome(-1, "", e.message ?: "Unknown error")
        }
    }

    fun defaultWorktreePathFor(branchName: String): String {
        val settings = com.jirasmartcommit.settings.PluginSettings.instance
        val configured = settings.vibeWorktreeBaseDir.trim()
        val repoRoot = getCurrentRepository()?.root?.path ?: project.basePath ?: ""
        val safeBranch = branchName.replace('/', '-').replace('\\', '-')

        val baseDir = if (configured.isNotBlank()) {
            File(configured)
        } else {
            val repoFile = File(repoRoot)
            val parent = repoFile.parentFile ?: repoFile
            File(parent, "${repoFile.name}-worktrees")
        }
        return File(baseDir, safeBranch).absolutePath
    }

    companion object {
        private const val DEFAULT_BASE_BRANCH = "main"
        private val COMMON_BASE_BRANCHES = listOf("main", "master", "develop", "development")

        // Doesn't need to exist — git treats a hooks dir with no matching hook file as "no hook".
        // Used to disable repo hooks for the `git worktree add` invocation only.
        private val NO_HOOKS_DIR = File(System.getProperty("java.io.tmpdir"), "jira-smart-commit-no-hooks").path

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
