package com.jirasmartcommit.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.jirasmartcommit.services.*
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.ui.CommitMessageDialog
import com.jirasmartcommit.util.ConventionalCommit
import kotlinx.coroutines.runBlocking

class GenerateCommitAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating Commit Message...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                runBlocking {
                    generateCommitMessage(project, indicator)
                }
            }
        })
    }

    private suspend fun generateCommitMessage(project: Project, indicator: ProgressIndicator) {
        val gitService = GitService.getInstance(project)
        val jiraService = JiraService.getInstance(project)
        val aiService = AIService.getInstance(project)
        val settings = PluginSettings.instance

        // Step 1: Get staged diff
        indicator.text = "Getting staged changes..."
        indicator.fraction = 0.1

        val diffResult = gitService.getStagedDiff()
        if (diffResult is GitResult.Error) {
            showError(project, diffResult.message)
            return
        }
        val diff = (diffResult as GitResult.Success).data

        // Step 2: Get staged files
        indicator.text = "Analyzing staged files..."
        indicator.fraction = 0.2

        val stagedFiles = gitService.getStagedFiles()
        if (stagedFiles.isEmpty()) {
            showError(project, "No staged changes found. Please stage your changes first.")
            return
        }

        // Step 3: Try to get JIRA context
        indicator.text = "Fetching JIRA ticket..."
        indicator.fraction = 0.3

        var jiraContext: String? = null
        var ticketKey: String? = null

        val currentBranch = gitService.getCurrentBranch()
        if (currentBranch != null && settings.isJiraConfigured()) {
            ticketKey = jiraService.extractTicketKeyFromBranch(currentBranch)
            if (ticketKey != null) {
                val ticketResult = jiraService.fetchTicket(ticketKey)
                if (ticketResult is JiraResult.Success) {
                    jiraContext = jiraService.buildTicketContext(ticketResult.data)
                }
            }
        }

        // Step 4: Generate commit message with AI
        indicator.text = "Generating commit message with AI..."
        indicator.fraction = 0.5

        val aiResult = aiService.generateCommitMessage(diff, jiraContext, stagedFiles)
        if (aiResult is AIResult.Error) {
            showError(project, aiResult.message)
            return
        }

        var commitMessage = (aiResult as AIResult.Success).data

        // Step 5: Add JIRA reference if configured
        if (ticketKey != null && settings.includeFooterWithJiraRef) {
            val parsed = ConventionalCommit.parse(commitMessage)
            if (parsed != null && (parsed.footer == null || !parsed.footer.contains(ticketKey))) {
                val withRef = ConventionalCommit.formatWithJiraRef(parsed, ticketKey)
                commitMessage = withRef.toString()
            }
        }

        indicator.fraction = 1.0

        // Check if remote exists
        val hasRemote = gitService.hasRemote()

        // Step 6: Show dialog on EDT
        val finalMessage = commitMessage
        val finalBranch = currentBranch
        ApplicationManager.getApplication().invokeLater {
            showCommitDialog(project, finalMessage, finalBranch, hasRemote, gitService)
        }
    }

    private fun showCommitDialog(
        project: Project,
        initialMessage: String,
        currentBranch: String?,
        hasRemote: Boolean,
        gitService: GitService
    ) {
        val dialog = CommitMessageDialog(
            project = project,
            initialMessage = initialMessage,
            currentBranch = currentBranch,
            hasRemote = hasRemote,
            onRegenerate = {
                // Regenerate callback - runs in background
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project,
                    "Regenerating Commit Message...",
                    true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        runBlocking {
                            regenerateMessage(project, indicator)
                        }
                    }
                })
            }
        )

        if (dialog.showAndGet()) {
            when {
                dialog.shouldPushOnly() -> {
                    // Push only - no commit
                    performPushOnly(project, currentBranch, gitService)
                }
                dialog.shouldCommit() -> {
                    val message = dialog.getCommitMessage()
                    val shouldPush = dialog.shouldPush()
                    if (message.isNotBlank()) {
                        performCommit(project, message, shouldPush, currentBranch, gitService)
                    }
                }
            }
        }
    }

    private suspend fun regenerateMessage(project: Project, indicator: ProgressIndicator) {
        // Re-run the generation logic
        generateCommitMessage(project, indicator)
    }

    private fun performPushOnly(
        project: Project,
        branchName: String?,
        gitService: GitService
    ) {
        if (branchName == null) {
            showError(project, "Cannot push: no branch detected")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Pushing to origin/$branchName...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val pushResult = gitService.push(branchName)

                ApplicationManager.getApplication().invokeLater {
                    when (pushResult) {
                        is GitResult.Success -> {
                            showNotification(
                                project,
                                "Pushed to origin/$branchName",
                                NotificationType.INFORMATION
                            )
                        }
                        is GitResult.Error -> {
                            showError(project, "Push failed: ${pushResult.message}")
                        }
                    }
                }
            }
        })
    }

    private fun performCommit(
        project: Project,
        message: String,
        shouldPush: Boolean,
        branchName: String?,
        gitService: GitService
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            if (shouldPush) "Committing and pushing..." else "Committing...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                // Step 1: Commit
                indicator.text = "Committing..."
                val commitResult = gitService.commit(message)

                when (commitResult) {
                    is GitResult.Success -> {
                        if (shouldPush && branchName != null) {
                            // Step 2: Push
                            indicator.text = "Pushing to origin/$branchName..."
                            val pushResult = gitService.push(branchName)

                            ApplicationManager.getApplication().invokeLater {
                                when (pushResult) {
                                    is GitResult.Success -> {
                                        showNotification(
                                            project,
                                            "Committed and pushed to origin/$branchName",
                                            NotificationType.INFORMATION
                                        )
                                    }
                                    is GitResult.Error -> {
                                        // Commit succeeded but push failed
                                        showNotification(
                                            project,
                                            "Commit successful, but push failed: ${pushResult.message}",
                                            NotificationType.WARNING
                                        )
                                    }
                                }
                            }
                        } else {
                            ApplicationManager.getApplication().invokeLater {
                                showNotification(
                                    project,
                                    "Commit successful",
                                    NotificationType.INFORMATION
                                )
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
            showNotification(project, message, NotificationType.ERROR)
        }
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JIRA Smart Commit")
            .createNotification(message, type)
            .notify(project)
    }
}
