package com.jirasmartcommit

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * JIRA Smart Commit Plugin
 *
 * This plugin provides AI-powered commit message and PR description generation
 * with JIRA integration for JetBrains IDEs.
 *
 * Features:
 * - Generate conventional commit messages from staged changes
 * - Automatically fetch JIRA ticket context from branch name
 * - Generate comprehensive PR descriptions from commit history
 * - Support for OpenAI and Anthropic AI providers
 *
 * Usage:
 * 1. Configure JIRA and AI credentials in Settings → Tools → JIRA Smart Commit
 * 2. Stage your changes
 * 3. Use VCS → JIRA Smart Commit → Generate Commit Message (Ctrl+Alt+G)
 * 4. For PR descriptions: VCS → JIRA Smart Commit → Generate PR Description (Ctrl+Alt+P)
 */
class JiraSmartCommitStartupActivity : ProjectActivity {

    private val logger = Logger.getInstance(JiraSmartCommitStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        logger.info("JIRA Smart Commit plugin initialized for project: ${project.name}")
    }
}
