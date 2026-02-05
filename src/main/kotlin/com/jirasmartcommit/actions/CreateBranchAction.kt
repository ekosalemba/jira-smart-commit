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
import com.intellij.openapi.ui.Messages
import com.jirasmartcommit.services.*
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.ui.CreateBranchDialog
import com.jirasmartcommit.util.BranchNameGenerator
import kotlinx.coroutines.runBlocking

class CreateBranchAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val gitService = project?.let { GitService.getInstance(it) }
        val settings = PluginSettings.instance

        // Enable only if we have a project, a git repository, and JIRA is configured
        val hasRepository = gitService?.getCurrentRepository() != null
        val isJiraConfigured = settings.isJiraConfigured()

        e.presentation.isEnabled = project != null && hasRepository && isJiraConfigured

        // Update description based on state
        e.presentation.description = when {
            project == null -> "No project open"
            !hasRepository -> "No Git repository found"
            !isJiraConfigured -> "JIRA is not configured"
            else -> "Create a Git branch named after a JIRA ticket"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = PluginSettings.instance
        val gitService = GitService.getInstance(project)

        // Check prerequisites
        if (!settings.isJiraConfigured()) {
            showError(project, "JIRA is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
            return
        }

        if (gitService.getCurrentRepository() == null) {
            showError(project, "No Git repository found in the current project")
            return
        }

        // Prompt for JIRA ticket key
        val ticketKey = Messages.showInputDialog(
            project,
            "Enter JIRA ticket key (e.g., BOT-123):",
            "Create Branch from JIRA Ticket",
            Messages.getQuestionIcon(),
            "",
            null
        )

        if (ticketKey.isNullOrBlank()) {
            return // User cancelled
        }

        // Normalize ticket key to uppercase
        val normalizedKey = ticketKey.trim().uppercase()

        // Fetch ticket and show dialog
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Fetching JIRA Ticket...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                runBlocking {
                    fetchTicketAndShowDialog(project, normalizedKey)
                }
            }
        })
    }

    private suspend fun fetchTicketAndShowDialog(project: Project, ticketKey: String) {
        val jiraService = JiraService.getInstance(project)
        val gitService = GitService.getInstance(project)
        val settings = PluginSettings.instance

        // Fetch the ticket
        val ticketResult = jiraService.fetchTicket(ticketKey)

        when (ticketResult) {
            is JiraResult.Success -> {
                val ticket = ticketResult.data

                // Generate branch name
                val generatedBranchName = BranchNameGenerator.generate(
                    ticketKey = ticket.key,
                    summary = ticket.summary,
                    issueType = ticket.issueType
                )

                // Check if branch already exists
                if (gitService.branchExists(generatedBranchName)) {
                    ApplicationManager.getApplication().invokeLater {
                        handleExistingBranch(project, generatedBranchName, gitService)
                    }
                    return
                }

                // Get available branches
                val availableBranches = gitService.getAvailableBaseBranches()
                val defaultBaseBranch = if (availableBranches.contains(settings.defaultBaseBranch)) {
                    settings.defaultBaseBranch
                } else {
                    availableBranches.firstOrNull() ?: "main"
                }

                // Show dialog on EDT
                ApplicationManager.getApplication().invokeLater {
                    showCreateBranchDialog(
                        project = project,
                        ticket = ticket,
                        availableBranches = availableBranches,
                        generatedBranchName = generatedBranchName,
                        defaultBaseBranch = defaultBaseBranch,
                        gitService = gitService
                    )
                }
            }
            is JiraResult.Error -> {
                val message = when {
                    ticketResult.message.contains("401") || ticketResult.message.contains("authentication") ->
                        "JIRA authentication failed. Please check your credentials in Settings → Tools → JIRA Smart Commit"
                    ticketResult.message.contains("403") || ticketResult.message.contains("Access denied") ->
                        "Access denied to JIRA ticket $ticketKey. Please check your permissions."
                    ticketResult.message.contains("404") || ticketResult.message.contains("not found") ->
                        "JIRA ticket $ticketKey not found"
                    else -> ticketResult.message
                }
                showError(project, message)
            }
        }
    }

    private fun handleExistingBranch(project: Project, branchName: String, gitService: GitService) {
        val result = Messages.showYesNoDialog(
            project,
            "Branch '$branchName' already exists.\n\nWould you like to checkout the existing branch?",
            "Branch Already Exists",
            "Checkout",
            "Cancel",
            Messages.getQuestionIcon()
        )

        if (result == Messages.YES) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "Checking out branch...",
                false
            ) {
                override fun run(indicator: ProgressIndicator) {
                    val checkoutResult = gitService.checkoutBranch(branchName)
                    ApplicationManager.getApplication().invokeLater {
                        when (checkoutResult) {
                            is GitResult.Success -> {
                                showNotification(
                                    project,
                                    "Switched to branch '$branchName'",
                                    NotificationType.INFORMATION
                                )
                            }
                            is GitResult.Error -> {
                                showError(project, checkoutResult.message)
                            }
                        }
                    }
                }
            })
        }
    }

    private fun showCreateBranchDialog(
        project: Project,
        ticket: JiraTicket,
        availableBranches: List<String>,
        generatedBranchName: String,
        defaultBaseBranch: String,
        gitService: GitService
    ) {
        val dialog = CreateBranchDialog(
            project = project,
            ticket = ticket,
            availableBranches = availableBranches,
            generatedBranchName = generatedBranchName,
            defaultBaseBranch = defaultBaseBranch
        )

        if (dialog.showAndGet()) {
            val branchName = dialog.getBranchName()
            val baseBranch = dialog.getBaseBranch()

            // Check if the final branch name exists (user might have edited it)
            if (gitService.branchExists(branchName)) {
                handleExistingBranch(project, branchName, gitService)
                return
            }

            // Create the branch
            createBranch(project, branchName, baseBranch, gitService)
        }
    }

    private fun createBranch(
        project: Project,
        branchName: String,
        baseBranch: String,
        gitService: GitService
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Creating branch...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val result = gitService.createAndCheckoutBranch(branchName, baseBranch)

                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is GitResult.Success -> {
                            showNotification(
                                project,
                                "Branch '$branchName' created and checked out",
                                NotificationType.INFORMATION
                            )
                        }
                        is GitResult.Error -> {
                            showError(project, result.message)
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
