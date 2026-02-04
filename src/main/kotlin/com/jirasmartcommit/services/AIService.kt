package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirasmartcommit.settings.AIProvider
import com.jirasmartcommit.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AIResult<out T> {
    data class Success<T>(val data: T) : AIResult<T>()
    data class Error(val message: String) : AIResult<Nothing>()
}

@Service(Service.Level.PROJECT)
class AIService(private val project: Project) {

    private val logger = Logger.getInstance(AIService::class.java)

    private val settings: PluginSettings
        get() = PluginSettings.instance

    private val openAIProvider by lazy { OpenAIProvider() }
    private val anthropicProvider by lazy { AnthropicProvider() }

    private val currentProvider: AIProviderInterface
        get() = when (settings.aiProvider) {
            AIProvider.OPENAI -> openAIProvider
            AIProvider.ANTHROPIC -> anthropicProvider
        }

    suspend fun generateCommitMessage(
        diff: String,
        jiraContext: String?,
        stagedFiles: List<String>
    ): AIResult<String> = withContext(Dispatchers.IO) {
        if (!settings.isAIConfigured()) {
            return@withContext AIResult.Error("AI provider is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
        }

        val prompt = buildCommitPrompt(diff, jiraContext, stagedFiles)

        try {
            val response = currentProvider.complete(
                apiKey = settings.aiApiKey,
                model = settings.aiModel,
                systemPrompt = COMMIT_SYSTEM_PROMPT,
                userPrompt = prompt,
                customEndpoint = settings.customEndpoint.takeIf { it.isNotBlank() }
            )
            AIResult.Success(response.trim())
        } catch (e: AIProviderException) {
            logger.error("AI provider error", e)
            AIResult.Error(e.message ?: "Unknown AI error")
        } catch (e: Exception) {
            logger.error("Unexpected error generating commit message", e)
            AIResult.Error("Failed to generate commit message: ${e.message}")
        }
    }

    suspend fun generatePRDescription(
        commits: List<String>,
        diff: String,
        jiraContext: String?,
        baseBranch: String,
        currentBranch: String
    ): AIResult<String> = withContext(Dispatchers.IO) {
        if (!settings.isAIConfigured()) {
            return@withContext AIResult.Error("AI provider is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
        }

        val prompt = buildPRPrompt(commits, diff, jiraContext, baseBranch, currentBranch)

        try {
            val response = currentProvider.complete(
                apiKey = settings.aiApiKey,
                model = settings.aiModel,
                systemPrompt = PR_SYSTEM_PROMPT,
                userPrompt = prompt,
                customEndpoint = settings.customEndpoint.takeIf { it.isNotBlank() }
            )
            AIResult.Success(response.trim())
        } catch (e: AIProviderException) {
            logger.error("AI provider error", e)
            AIResult.Error(e.message ?: "Unknown AI error")
        } catch (e: Exception) {
            logger.error("Unexpected error generating PR description", e)
            AIResult.Error("Failed to generate PR description: ${e.message}")
        }
    }

    private fun buildCommitPrompt(diff: String, jiraContext: String?, stagedFiles: List<String>): String {
        return buildString {
            appendLine("Generate a conventional commit message for the following changes.")
            appendLine()

            if (jiraContext != null) {
                appendLine("=== JIRA CONTEXT ===")
                appendLine(jiraContext)
                appendLine()
            }

            appendLine("=== STAGED FILES ===")
            stagedFiles.forEach { appendLine("- $it") }
            appendLine()

            appendLine("=== DIFF ===")
            appendLine(diff.take(MAX_DIFF_LENGTH))
            if (diff.length > MAX_DIFF_LENGTH) {
                appendLine()
                appendLine("... (diff truncated)")
            }
        }
    }

    private fun buildPRPrompt(
        commits: List<String>,
        diff: String,
        jiraContext: String?,
        baseBranch: String,
        currentBranch: String
    ): String {
        return buildString {
            appendLine("Generate a comprehensive PR description for the following changes.")
            appendLine()
            appendLine("Branch: $currentBranch → $baseBranch")
            appendLine()

            if (jiraContext != null) {
                appendLine("=== JIRA CONTEXT ===")
                appendLine(jiraContext)
                appendLine()
            }

            appendLine("=== COMMITS ===")
            commits.forEach { appendLine("- $it") }
            appendLine()

            appendLine("=== DIFF SUMMARY ===")
            appendLine(diff.take(MAX_DIFF_LENGTH))
            if (diff.length > MAX_DIFF_LENGTH) {
                appendLine()
                appendLine("... (diff truncated)")
            }
        }
    }

    companion object {
        private const val MAX_DIFF_LENGTH = 8000

        private val COMMIT_SYSTEM_PROMPT = """
            You are an expert at writing clear, concise conventional commit messages.

            Follow these rules:
            1. Use conventional commit format: <type>(<scope>): <subject>
            2. Types: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert
            3. Scope is optional but recommended (e.g., api, ui, auth)
            4. Subject should be imperative mood, lowercase, no period at end
            5. Keep subject under 72 characters
            6. If JIRA ticket is provided, add footer: Refs: <TICKET-KEY>
            7. Only include body if changes are complex and need explanation

            Respond ONLY with the commit message, no explanations or markdown code blocks.

            Examples:
            - feat(auth): add OAuth2 login support
            - fix(api): handle null response from external service
            - refactor(ui): extract button component for reuse
        """.trimIndent()

        private val PR_SYSTEM_PROMPT = """
            You are an expert at writing clear, comprehensive PR descriptions.

            Generate a PR description with this structure:

            ## Summary
            A brief 2-3 sentence overview of what this PR does and why.

            ## Changes
            - Bullet points of specific changes made
            - Group related changes together
            - Be specific but concise

            ## Testing
            - How to test these changes
            - Any specific test cases to verify

            ## Related
            - Link to JIRA ticket if provided (use format: [TICKET-KEY])
            - Any other relevant context

            Keep the description focused and avoid unnecessary verbosity.
            Use markdown formatting appropriately.
        """.trimIndent()

        fun getInstance(project: Project): AIService {
            return project.getService(AIService::class.java)
        }
    }
}
