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
import com.jirasmartcommit.services.GitService
import com.jirasmartcommit.services.JiraResult
import com.jirasmartcommit.services.JiraService
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.ui.JiraTicketDialog
import com.jirasmartcommit.ui.JiraTicketNotFoundDialog
import kotlinx.coroutines.runBlocking

class FetchJiraTicketAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val settings = PluginSettings.instance
        e.presentation.isEnabled = project != null && settings.isJiraConfigured()

        if (!settings.isJiraConfigured()) {
            e.presentation.description = "JIRA is not configured. Please configure in Settings."
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = PluginSettings.instance

        if (!settings.isJiraConfigured()) {
            showError(project, "JIRA is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
            return
        }

        // Try to extract ticket key from branch name
        val gitService = GitService.getInstance(project)
        val jiraService = JiraService.getInstance(project)

        val currentBranch = gitService.getCurrentBranch()
        var ticketKey = currentBranch?.let { jiraService.extractTicketKeyFromBranch(it) }

        // If no ticket found in branch, ask user to enter one
        if (ticketKey == null) {
            ApplicationManager.getApplication().invokeLater {
                ticketKey = Messages.showInputDialog(
                    project,
                    "Enter JIRA ticket key (e.g., BOT-1234):",
                    "Fetch JIRA Ticket",
                    null,
                    "",
                    null
                )

                if (!ticketKey.isNullOrBlank()) {
                    fetchTicket(project, ticketKey!!, jiraService)
                }
            }
        } else {
            fetchTicket(project, ticketKey!!, jiraService)
        }
    }

    private fun fetchTicket(project: Project, ticketKey: String, jiraService: JiraService) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Fetching JIRA Ticket...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                runBlocking {
                    val result = jiraService.fetchTicket(ticketKey.uppercase())

                    ApplicationManager.getApplication().invokeLater {
                        when (result) {
                            is JiraResult.Success -> {
                                val dialog = JiraTicketDialog(project, result.data)
                                dialog.show()
                            }
                            is JiraResult.Error -> {
                                val dialog = JiraTicketNotFoundDialog(project, ticketKey, result.message)
                                dialog.show()
                            }
                        }
                    }
                }
            }
        })
    }

    private fun showError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JIRA Smart Commit")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
