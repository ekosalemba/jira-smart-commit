package com.jirasmartcommit.flows

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jirasmartcommit.services.AIResult
import com.jirasmartcommit.services.AIService
import com.jirasmartcommit.services.FileChange
import com.jirasmartcommit.services.GitResult
import com.jirasmartcommit.services.GitService
import com.jirasmartcommit.services.JiraResult
import com.jirasmartcommit.services.JiraService
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.ui.CommitMessageDialog
import com.jirasmartcommit.ui.FileSelectionDialog
import com.jirasmartcommit.util.CommitTypeInferrer
import com.jirasmartcommit.util.ConventionalCommit
import com.jirasmartcommit.util.DiffAnalyzer
import kotlinx.coroutines.runBlocking

object CommitFlow {

    private const val DEFAULT_TICKET_KEY = "BOT-000"

    fun run(project: Project, rootDir: VirtualFile, ticketKeyHint: String? = null) {
        val gitService = GitService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Scanning for changes...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val changedResult = gitService.getAllChangedFiles(rootDir)
                if (changedResult is GitResult.Error) {
                    showError(project, changedResult.message)
                    return
                }
                val changed = (changedResult as GitResult.Success).data
                if (changed.isEmpty()) {
                    showError(project, "No changes found in ${rootDir.path}.")
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    showFileSelectionDialog(project, rootDir, changed, ticketKeyHint)
                }
            }
        })
    }

    private fun showFileSelectionDialog(
        project: Project,
        rootDir: VirtualFile,
        changedFiles: List<FileChange>,
        ticketKeyHint: String?
    ) {
        val dialog = FileSelectionDialog(project, changedFiles)
        if (!dialog.showAndGet()) return

        val selected = dialog.getSelectedFiles()
        if (selected.isEmpty()) {
            showError(project, "No files selected.")
            return
        }

        val gitService = GitService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating Commit Message...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                val filesToStage = selected
                    .filter { !it.isStaged || it.hasUnstagedChanges }
                    .map { it.path }
                val filesToUnstage = dialog.getDeselectedFiles()
                    .filter { it.isStaged }
                    .map { it.path }

                indicator.text = "Updating staged files..."
                indicator.fraction = 0.05

                if (filesToUnstage.isNotEmpty()) {
                    val res = gitService.unstageFiles(rootDir, filesToUnstage)
                    if (res is GitResult.Error) {
                        showError(project, res.message)
                        return
                    }
                }
                if (filesToStage.isNotEmpty()) {
                    val res = gitService.stageFiles(rootDir, filesToStage)
                    if (res is GitResult.Error) {
                        showError(project, res.message)
                        return
                    }
                }

                runBlocking {
                    generateAndShow(project, rootDir, indicator, ticketKeyHint)
                }
            }
        })
    }

    private suspend fun generateAndShow(
        project: Project,
        rootDir: VirtualFile,
        indicator: ProgressIndicator,
        ticketKeyHint: String?
    ) {
        val gitService = GitService.getInstance(project)
        val jiraService = JiraService.getInstance(project)
        val aiService = AIService.getInstance(project)
        val settings = PluginSettings.instance

        indicator.text = "Getting staged changes..."
        indicator.fraction = 0.1

        val diffResult = gitService.getStagedDiff(rootDir)
        if (diffResult is GitResult.Error) {
            showError(project, diffResult.message)
            return
        }
        val diff = (diffResult as GitResult.Success).data

        indicator.text = "Analyzing staged files..."
        indicator.fraction = 0.2
        val stagedFiles = gitService.getStagedFiles(rootDir)
        if (stagedFiles.isEmpty()) {
            showError(project, "No staged changes found.")
            return
        }

        indicator.text = "Analyzing changes..."
        indicator.fraction = 0.25

        val nameStatusResult = gitService.getStagedNameStatus(rootDir)
        val nameStatusLines = when (nameStatusResult) {
            is GitResult.Success -> nameStatusResult.data
            is GitResult.Error -> emptyList()
        }

        val diffAnalysis = if (nameStatusLines.isNotEmpty()) {
            DiffAnalyzer.analyzeFromNameStatus(nameStatusLines, diff)
        } else {
            DiffAnalyzer.analyzeFromFileChanges(stagedFiles, diff)
        }

        val metadata = CommitTypeInferrer.infer(diffAnalysis, stagedFiles)

        indicator.text = "Fetching JIRA ticket..."
        indicator.fraction = 0.3

        val currentBranch = gitService.getCurrentBranch(rootDir)
        var jiraContext: String? = null
        var ticketKey: String? = ticketKeyHint

        if (ticketKey == null && currentBranch != null) {
            ticketKey = jiraService.extractTicketKeyFromBranch(currentBranch)
        }
        if (ticketKey != null && settings.isJiraConfigured()) {
            val ticketResult = jiraService.fetchTicket(ticketKey)
            if (ticketResult is JiraResult.Success) {
                jiraContext = jiraService.buildTicketContext(ticketResult.data)
            }
        }

        indicator.text = "Checking commit history..."
        indicator.fraction = 0.4
        val commitHistory = ticketKey?.let { gitService.getCommitHistoryForTicket(it) }

        indicator.text = "Generating commit message with AI..."
        indicator.fraction = 0.5

        val aiResult = aiService.generateCommitMessage(
            diff = diff,
            jiraContext = jiraContext,
            stagedFiles = stagedFiles,
            metadata = metadata,
            currentBranch = currentBranch,
            commitHistory = commitHistory
        )
        if (aiResult is AIResult.Error) {
            showError(project, aiResult.message)
            return
        }

        var commitMessage = (aiResult as AIResult.Success).data

        val refTicket = ticketKey ?: DEFAULT_TICKET_KEY
        if (settings.includeFooterWithJiraRef) {
            val parsed = ConventionalCommit.parse(commitMessage)
            if (parsed != null && (parsed.footer == null || !parsed.footer.contains(refTicket))) {
                commitMessage = ConventionalCommit.formatWithJiraRef(parsed, refTicket).toString()
            }
        }

        indicator.fraction = 1.0
        val hasRemote = gitService.hasRemote()

        val finalMessage = commitMessage
        val finalBranch = currentBranch
        ApplicationManager.getApplication().invokeLater {
            showCommitDialog(project, rootDir, finalMessage, finalBranch, hasRemote, ticketKeyHint)
        }
    }

    private fun showCommitDialog(
        project: Project,
        rootDir: VirtualFile,
        initialMessage: String,
        currentBranch: String?,
        hasRemote: Boolean,
        ticketKeyHint: String?
    ) {
        val dialog = CommitMessageDialog(
            project = project,
            initialMessage = initialMessage,
            currentBranch = currentBranch,
            hasRemote = hasRemote,
            onRegenerate = {
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project,
                    "Regenerating Commit Message...",
                    true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        runBlocking { generateAndShow(project, rootDir, indicator, ticketKeyHint) }
                    }
                })
            }
        )

        if (dialog.showAndGet()) {
            when {
                dialog.shouldPushOnly() -> performPushOnly(project, rootDir, currentBranch)
                dialog.shouldCommit() -> {
                    val message = dialog.getCommitMessage()
                    val shouldPush = dialog.shouldPush()
                    if (message.isNotBlank()) {
                        performCommit(project, rootDir, message, shouldPush, currentBranch)
                    }
                }
            }
        }
    }

    private fun performPushOnly(project: Project, rootDir: VirtualFile, branchName: String?) {
        if (branchName == null) {
            showError(project, "Cannot push: no branch detected")
            return
        }
        val gitService = GitService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Pushing to origin/$branchName...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val pushResult = gitService.push(rootDir, branchName)
                ApplicationManager.getApplication().invokeLater {
                    when (pushResult) {
                        is GitResult.Success -> notify(project, "Pushed to origin/$branchName", NotificationType.INFORMATION)
                        is GitResult.Error -> showError(project, "Push failed: ${pushResult.message}")
                    }
                }
            }
        })
    }

    private fun performCommit(
        project: Project,
        rootDir: VirtualFile,
        message: String,
        shouldPush: Boolean,
        branchName: String?
    ) {
        val gitService = GitService.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            if (shouldPush) "Committing and pushing..." else "Committing...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Committing..."
                val commitResult = gitService.commit(rootDir, message)

                when (commitResult) {
                    is GitResult.Success -> {
                        LocalFileSystem.getInstance().refresh(false)
                        if (shouldPush && branchName != null) {
                            indicator.text = "Pushing to origin/$branchName..."
                            val pushResult = gitService.push(rootDir, branchName)
                            ApplicationManager.getApplication().invokeLater {
                                when (pushResult) {
                                    is GitResult.Success -> notify(project, "Committed and pushed to origin/$branchName", NotificationType.INFORMATION)
                                    is GitResult.Error -> notify(project, "Commit OK, push failed: ${pushResult.message}", NotificationType.WARNING)
                                }
                            }
                        } else {
                            ApplicationManager.getApplication().invokeLater {
                                notify(project, "Commit successful", NotificationType.INFORMATION)
                            }
                        }
                    }
                    is GitResult.Error -> {
                        ApplicationManager.getApplication().invokeLater {
                            showError(project, commitResult.message)
                        }
                    }
                }
            }
        })
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            notify(project, message, NotificationType.ERROR)
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JIRA Smart Commit")
            .createNotification(message, type)
            .notify(project)
    }
}
