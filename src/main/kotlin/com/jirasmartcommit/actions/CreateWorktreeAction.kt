package com.jirasmartcommit.actions

import com.intellij.ide.impl.ProjectUtil
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
import com.intellij.openapi.vfs.LocalFileSystem
import com.jirasmartcommit.services.*
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.ui.CreateWorktreeDialog
import com.jirasmartcommit.util.BranchNameGenerator
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths

class CreateWorktreeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val gitService = project?.let { GitService.getInstance(it) }
        val settings = PluginSettings.instance

        val hasRepository = gitService?.getCurrentRepository() != null
        val isJiraConfigured = settings.isJiraConfigured()

        e.presentation.isEnabled = project != null && hasRepository && isJiraConfigured

        e.presentation.description = when {
            project == null -> "No project open"
            !hasRepository -> "No Git repository found"
            !isJiraConfigured -> "JIRA is not configured"
            else -> "Create a Git worktree on a new branch named after a JIRA ticket"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = PluginSettings.instance
        val gitService = GitService.getInstance(project)

        if (!settings.isJiraConfigured()) {
            showError(project, "JIRA is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
            return
        }

        if (gitService.getCurrentRepository() == null) {
            showError(project, "No Git repository found in the current project")
            return
        }

        val ticketKey = Messages.showInputDialog(
            project,
            "Enter JIRA ticket key (e.g., BOT-123):",
            "Create Worktree from JIRA Ticket",
            Messages.getQuestionIcon(),
            "",
            null
        )

        if (ticketKey.isNullOrBlank()) {
            return
        }

        val normalizedKey = ticketKey.trim().uppercase()

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

        val ticketResult = jiraService.fetchTicket(ticketKey)

        when (ticketResult) {
            is JiraResult.Success -> {
                val ticket = ticketResult.data

                val generatedBranchName = BranchNameGenerator.generate(
                    ticketKey = ticket.key,
                    summary = ticket.summary,
                    issueType = ticket.issueType
                )

                val availableBranches = gitService.getAvailableBaseBranches()
                val defaultBaseBranch = if (availableBranches.contains(settings.defaultBaseBranch)) {
                    settings.defaultBaseBranch
                } else {
                    availableBranches.firstOrNull() ?: "main"
                }

                ApplicationManager.getApplication().invokeLater {
                    showCreateWorktreeDialog(
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

    private fun showCreateWorktreeDialog(
        project: Project,
        ticket: JiraTicket,
        availableBranches: List<String>,
        generatedBranchName: String,
        defaultBaseBranch: String,
        gitService: GitService
    ) {
        val dialog = CreateWorktreeDialog(
            project = project,
            ticket = ticket,
            availableBranches = availableBranches,
            generatedBranchName = generatedBranchName,
            defaultBaseBranch = defaultBaseBranch
        )

        if (dialog.showAndGet()) {
            createWorktree(
                project = project,
                branchName = dialog.getBranchName(),
                baseBranch = dialog.getBaseBranch(),
                worktreePath = dialog.getWorktreePath(),
                fetchBaseFirst = dialog.fetchBaseFirst(),
                openInNewWindow = dialog.openInNewWindow(),
                gitService = gitService
            )
        }
    }

    private fun createWorktree(
        project: Project,
        branchName: String,
        baseBranch: String,
        worktreePath: String,
        fetchBaseFirst: Boolean,
        openInNewWindow: Boolean,
        gitService: GitService
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Creating worktree...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                if (fetchBaseFirst) {
                    indicator.text = "Fetching '$baseBranch' from origin..."
                    val fetchResult = gitService.fetchRemote("origin", baseBranch)
                    if (fetchResult is GitResult.Error) {
                        showError(project, "Fetch failed: ${fetchResult.message}")
                        return
                    }
                }

                indicator.text = "Adding worktree..."
                val createBranch = !gitService.branchExistsAnywhere(branchName)
                val result = gitService.addWorktree(worktreePath, branchName, baseBranch, createBranch)

                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is GitResult.Success -> {
                            showNotification(
                                project,
                                "Worktree created at '$worktreePath' on branch '$branchName'",
                                NotificationType.INFORMATION
                            )
                            if (openInNewWindow) {
                                openWorktree(worktreePath)
                            }
                        }
                        is GitResult.Error -> {
                            showError(project, result.message)
                        }
                    }
                }
            }
        })
    }

    private fun openWorktree(worktreePath: String) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(worktreePath)
        ProjectUtil.openOrImport(Paths.get(File(worktreePath).absolutePath), null, true)
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
