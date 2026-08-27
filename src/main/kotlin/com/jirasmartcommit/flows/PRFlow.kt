package com.jirasmartcommit.flows

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jirasmartcommit.services.AIResult
import com.jirasmartcommit.services.AIService
import com.jirasmartcommit.services.GitResult
import com.jirasmartcommit.services.GitService
import com.jirasmartcommit.services.JiraResult
import com.jirasmartcommit.services.JiraService
import com.jirasmartcommit.services.PRContent
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.ui.PRDescriptionDialog
import com.jirasmartcommit.util.CommitAnalyzer
import com.jirasmartcommit.util.PRScoreCalculator
import com.jirasmartcommit.util.PRValidator
import com.jirasmartcommit.util.ScoreBreakdown
import kotlinx.coroutines.runBlocking

object PRFlow {

    fun run(
        project: Project,
        rootDir: VirtualFile,
        preferredBaseBranch: String? = null,
        ticketKeyHint: String? = null
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating PR Description...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                runBlocking {
                    generate(project, rootDir, preferredBaseBranch, ticketKeyHint, indicator)
                }
            }
        })
    }

    private suspend fun generate(
        project: Project,
        rootDir: VirtualFile,
        preferredBaseBranch: String?,
        ticketKeyHint: String?,
        indicator: ProgressIndicator
    ) {
        val gitService = GitService.getInstance(project)
        val jiraService = JiraService.getInstance(project)
        val aiService = AIService.getInstance(project)
        val settings = PluginSettings.instance

        indicator.text = "Analyzing branches..."
        indicator.fraction = 0.1

        val currentBranch = gitService.getCurrentBranch(rootDir)
        if (currentBranch == null) {
            showError(project, "Could not determine current branch")
            return
        }

        val baseBranch = preferredBaseBranch ?: gitService.getBaseBranch()

        indicator.text = "Getting commit history..."
        indicator.fraction = 0.2

        val commitsResult = gitService.getCommitsSinceBranch(rootDir, baseBranch)
        val commits = when (commitsResult) {
            is GitResult.Success -> commitsResult.data
            is GitResult.Error -> emptyList()
        }
        if (commits.isEmpty()) {
            showError(project, "No commits found on '$currentBranch' since '$baseBranch'")
            return
        }

        indicator.text = "Getting diff summary..."
        indicator.fraction = 0.3
        val diff = when (val r = gitService.getDiffSinceBranch(rootDir, baseBranch)) {
            is GitResult.Success -> r.data
            is GitResult.Error -> ""
        }

        indicator.text = "Fetching JIRA ticket..."
        indicator.fraction = 0.4

        var jiraContext: String? = null
        var ticketKey: String? = ticketKeyHint
        if (settings.isJiraConfigured()) {
            if (ticketKey == null) {
                ticketKey = jiraService.extractTicketKeyFromBranch(currentBranch)
            }
            if (ticketKey != null) {
                val result = jiraService.fetchTicket(ticketKey)
                if (result is JiraResult.Success) {
                    jiraContext = jiraService.buildTicketContext(result.data)
                }
            }
        }

        indicator.text = "Analyzing commits..."
        indicator.fraction = 0.5
        val commitAnalysis = CommitAnalyzer.analyze(commits)

        indicator.text = "Generating PR description with AI..."
        indicator.fraction = 0.6

        val aiResult = aiService.generatePRDescription(
            commits = commits,
            diff = diff,
            jiraContext = jiraContext,
            baseBranch = baseBranch,
            currentBranch = currentBranch,
            commitAnalysis = commitAnalysis
        )

        if (aiResult is AIResult.Error) {
            showError(project, aiResult.message)
            return
        }

        var prContent = (aiResult as AIResult.Success).data

        if (ticketKey != null && settings.isJiraConfigured()) {
            val jiraUrl = settings.jiraUrl
            if (!prContent.description.contains(ticketKey)) {
                prContent = prContent.copy(
                    description = prContent.description + "\n\n## Related\n- [$ticketKey]($jiraUrl/browse/$ticketKey)"
                )
            }
        }

        indicator.text = "Calculating quality score..."
        indicator.fraction = 0.9

        val validation = PRValidator.validate(prContent.description)
        val score = PRScoreCalculator.calculate(
            validation = validation,
            commitAnalysis = commitAnalysis,
            jiraContext = jiraContext,
            description = prContent.description
        )

        indicator.fraction = 1.0

        val availableBranches = gitService.getAvailableBaseBranches()

        val finalContent = prContent
        val finalScore = score
        ApplicationManager.getApplication().invokeLater {
            showDialog(
                project = project,
                rootDir = rootDir,
                prContent = finalContent,
                currentBranch = currentBranch,
                baseBranch = baseBranch,
                availableBranches = availableBranches,
                preferredBaseBranch = preferredBaseBranch,
                ticketKeyHint = ticketKeyHint,
                score = finalScore
            )
        }
    }

    private fun showDialog(
        project: Project,
        rootDir: VirtualFile,
        prContent: PRContent,
        currentBranch: String,
        baseBranch: String,
        availableBranches: List<String>,
        preferredBaseBranch: String?,
        ticketKeyHint: String?,
        score: ScoreBreakdown?
    ) {
        val gitService = GitService.getInstance(project)
        val dialog = PRDescriptionDialog(
            project = project,
            initialTitle = prContent.title,
            initialDescription = prContent.description,
            availableBranches = availableBranches,
            defaultBaseBranch = baseBranch,
            currentBranch = currentBranch,
            gitService = gitService,
            score = score,
            onRegenerate = {
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project,
                    "Regenerating PR Description...",
                    true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        runBlocking { generate(project, rootDir, preferredBaseBranch, ticketKeyHint, indicator) }
                    }
                })
            }
        )

        dialog.show()

        when {
            dialog.wasPRCreated() -> Unit
            dialog.wasCopied() -> notify(
                project,
                "PR title and description copied to clipboard",
                NotificationType.INFORMATION
            )
        }
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
