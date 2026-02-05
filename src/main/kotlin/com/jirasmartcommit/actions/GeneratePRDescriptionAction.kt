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
import com.jirasmartcommit.ui.PRDescriptionDialog
import kotlinx.coroutines.runBlocking

class GeneratePRDescriptionAction : AnAction() {

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
            "Generating PR Description...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0

                runBlocking {
                    generatePRDescription(project, indicator)
                }
            }
        })
    }

    private suspend fun generatePRDescription(project: Project, indicator: ProgressIndicator) {
        val gitService = GitService.getInstance(project)
        val jiraService = JiraService.getInstance(project)
        val aiService = AIService.getInstance(project)
        val settings = PluginSettings.instance

        // Step 1: Get current and base branch
        indicator.text = "Analyzing branches..."
        indicator.fraction = 0.1

        val currentBranch = gitService.getCurrentBranch()
        if (currentBranch == null) {
            showError(project, "Could not determine current branch")
            return
        }

        val baseBranch = gitService.getBaseBranch()

        // Step 2: Get commits since base branch
        indicator.text = "Getting commit history..."
        indicator.fraction = 0.2

        val commitsResult = gitService.getCommitsSinceBranch(baseBranch)
        val commits = when (commitsResult) {
            is GitResult.Success -> commitsResult.data
            is GitResult.Error -> {
                // If we can't get commits, try to continue with an empty list
                emptyList()
            }
        }

        if (commits.isEmpty()) {
            showError(project, "No commits found on branch '$currentBranch' since '$baseBranch'")
            return
        }

        // Step 3: Get diff summary
        indicator.text = "Getting diff summary..."
        indicator.fraction = 0.3

        val diffResult = gitService.getDiffSinceBranch(baseBranch)
        val diff = when (diffResult) {
            is GitResult.Success -> diffResult.data
            is GitResult.Error -> ""
        }

        // Step 4: Try to get JIRA context
        indicator.text = "Fetching JIRA ticket..."
        indicator.fraction = 0.4

        var jiraContext: String? = null
        var ticketKey: String? = null

        if (settings.isJiraConfigured()) {
            ticketKey = jiraService.extractTicketKeyFromBranch(currentBranch)
            if (ticketKey != null) {
                val ticketResult = jiraService.fetchTicket(ticketKey)
                if (ticketResult is JiraResult.Success) {
                    jiraContext = jiraService.buildTicketContext(ticketResult.data)
                }
            }
        }

        // Step 5: Generate PR description with AI
        indicator.text = "Generating PR description with AI..."
        indicator.fraction = 0.6

        val aiResult = aiService.generatePRDescription(
            commits = commits,
            diff = diff,
            jiraContext = jiraContext,
            baseBranch = baseBranch,
            currentBranch = currentBranch
        )

        if (aiResult is AIResult.Error) {
            showError(project, aiResult.message)
            return
        }

        var prContent = (aiResult as AIResult.Success).data

        // Step 6: Add JIRA link if available
        if (ticketKey != null && settings.isJiraConfigured()) {
            val jiraUrl = settings.jiraUrl
            if (!prContent.description.contains(ticketKey)) {
                prContent = prContent.copy(
                    description = prContent.description + "\n\n## Related\n- [$ticketKey]($jiraUrl/browse/$ticketKey)"
                )
            }
        }

        indicator.fraction = 1.0

        // Step 7: Get available branches
        val availableBranches = gitService.getAvailableBaseBranches()

        // Step 8: Show dialog on EDT
        val finalPRContent = prContent
        val finalCurrentBranch = currentBranch
        val finalBaseBranch = baseBranch
        val finalAvailableBranches = availableBranches
        ApplicationManager.getApplication().invokeLater {
            showPRDialog(project, finalPRContent, finalCurrentBranch, finalBaseBranch, finalAvailableBranches, gitService)
        }
    }

    private fun showPRDialog(
        project: Project,
        prContent: PRContent,
        currentBranch: String,
        baseBranch: String,
        availableBranches: List<String>,
        gitService: GitService
    ) {
        val dialog = PRDescriptionDialog(
            project = project,
            initialTitle = prContent.title,
            initialDescription = prContent.description,
            availableBranches = availableBranches,
            defaultBaseBranch = baseBranch,
            currentBranch = currentBranch,
            gitService = gitService,
            onRegenerate = {
                // Regenerate callback - runs in background
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project,
                    "Regenerating PR Description...",
                    true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        runBlocking {
                            generatePRDescription(project, indicator)
                        }
                    }
                })
            }
        )

        dialog.show()

        when {
            dialog.wasPRCreated() -> {
                // Notification already shown in dialog
            }
            dialog.wasCopied() -> {
                showNotification(
                    project,
                    "PR title and description copied to clipboard",
                    NotificationType.INFORMATION
                )
            }
        }
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
