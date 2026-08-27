package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirasmartcommit.settings.AIProvider
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.util.CommitAnalysis
import com.jirasmartcommit.util.CommitAnalyzer
import com.jirasmartcommit.util.CommitMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AIResult<out T> {
    data class Success<T>(val data: T) : AIResult<T>()
    data class Error(val message: String) : AIResult<Nothing>()
}

data class PRContent(
    val title: String,
    val description: String
)

@Service(Service.Level.PROJECT)
class AIService(private val project: Project) {

    private val logger = Logger.getInstance(AIService::class.java)

    private val settings: PluginSettings
        get() = PluginSettings.instance

    private val openAIProvider by lazy { OpenAIProvider() }
    private val anthropicProvider by lazy { AnthropicProvider() }
    private val claudeCodeProvider by lazy { ClaudeCodeProvider() }

    private val currentProvider: AIProviderInterface
        get() = when (settings.aiProvider) {
            AIProvider.OPENAI -> openAIProvider
            AIProvider.ANTHROPIC -> anthropicProvider
            AIProvider.CLAUDE_CODE -> claudeCodeProvider
        }

    suspend fun generateCommitMessage(
        diff: String,
        jiraContext: String?,
        stagedFiles: List<String>,
        metadata: CommitMetadata? = null,
        currentBranch: String? = null,
        commitHistory: List<String>? = null
    ): AIResult<String> = withContext(Dispatchers.IO) {
        if (!settings.isAIConfigured()) {
            return@withContext AIResult.Error("AI provider is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
        }

        val prompt = buildCommitPrompt(diff, jiraContext, stagedFiles, metadata, currentBranch, commitHistory)

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

    suspend fun completeWithCustomPrompt(
        systemPrompt: String,
        userPrompt: String
    ): AIResult<String> = withContext(Dispatchers.IO) {
        if (!settings.isAIConfigured()) {
            return@withContext AIResult.Error("AI provider is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
        }

        try {
            val response = currentProvider.complete(
                apiKey = settings.aiApiKey,
                model = settings.aiModel,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                customEndpoint = settings.customEndpoint.takeIf { it.isNotBlank() }
            )
            AIResult.Success(response.trim())
        } catch (e: AIProviderException) {
            logger.error("AI provider error", e)
            AIResult.Error(e.message ?: "Unknown AI error")
        } catch (e: Exception) {
            logger.error("Unexpected error in custom prompt completion", e)
            AIResult.Error("Failed to complete: ${e.message}")
        }
    }

    suspend fun generatePRDescription(
        commits: List<String>,
        diff: String,
        jiraContext: String?,
        baseBranch: String,
        currentBranch: String,
        commitAnalysis: CommitAnalysis? = null
    ): AIResult<PRContent> = withContext(Dispatchers.IO) {
        if (!settings.isAIConfigured()) {
            return@withContext AIResult.Error("AI provider is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
        }

        val prompt = buildPRPrompt(commits, diff, jiraContext, baseBranch, currentBranch, commitAnalysis)

        try {
            val response = currentProvider.complete(
                apiKey = settings.aiApiKey,
                model = settings.aiModel,
                systemPrompt = PR_SYSTEM_PROMPT,
                userPrompt = prompt,
                customEndpoint = settings.customEndpoint.takeIf { it.isNotBlank() }
            )
            val prContent = parsePRResponse(response.trim())
            AIResult.Success(prContent)
        } catch (e: AIProviderException) {
            logger.error("AI provider error", e)
            AIResult.Error(e.message ?: "Unknown AI error")
        } catch (e: Exception) {
            logger.error("Unexpected error generating PR description", e)
            AIResult.Error("Failed to generate PR description: ${e.message}")
        }
    }

    private fun parsePRResponse(response: String): PRContent {
        val lines = response.lines()
        var title = ""
        val descriptionLines = mutableListOf<String>()
        var foundTitle = false

        for (line in lines) {
            if (!foundTitle && line.startsWith("TITLE:")) {
                title = line.removePrefix("TITLE:").trim()
                foundTitle = true
            } else if (foundTitle) {
                descriptionLines.add(line)
            }
        }

        // If no TITLE: prefix found, try to extract from first line or generate from branch
        if (title.isEmpty()) {
            val firstLine = lines.firstOrNull()?.trim() ?: ""
            if (firstLine.startsWith("#")) {
                title = firstLine.removePrefix("#").trim()
                descriptionLines.clear()
                descriptionLines.addAll(lines.drop(1))
            } else {
                title = "Pull Request"
                descriptionLines.clear()
                descriptionLines.addAll(lines)
            }
        }

        val description = descriptionLines
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()

        return PRContent(title = "[DONE][FULL_COPILOT] $title", description = description)
    }

    private fun buildCommitPrompt(
        diff: String,
        jiraContext: String?,
        stagedFiles: List<String>,
        metadata: CommitMetadata? = null,
        currentBranch: String? = null,
        commitHistory: List<String>? = null
    ): String {
        // Token budget: allocate space between JIRA context and diff
        val totalBudget = MAX_DIFF_LENGTH
        val jiraContextBudget: Int
        val diffBudget: Int

        if (jiraContext != null) {
            // Balanced: 40% JIRA description, 60% diff
            jiraContextBudget = (totalBudget * 0.4).toInt()
            diffBudget = totalBudget - jiraContextBudget
        } else {
            jiraContextBudget = 0
            diffBudget = totalBudget
        }

        return buildString {
            appendLine("Context:")
            if (currentBranch != null) {
                appendLine("- Branch: $currentBranch")
            }

            if (jiraContext != null) {
                appendLine()
                appendLine("=== JIRA CONTEXT ===")
                appendLine(smartTruncate(jiraContext, jiraContextBudget))
            }

            if (!commitHistory.isNullOrEmpty()) {
                appendLine()
                appendLine("=== PREVIOUS COMMITS FOR THIS TICKET ===")
                commitHistory.take(10).forEach { appendLine("- $it") }
            }

            appendLine()

            if (metadata != null) {
                appendLine("Detected metadata:")
                appendLine("- type: ${metadata.suggestedType}")
                appendLine("- scope: ${metadata.suggestedScope ?: "auto"}")
                appendLine("- breaking: ${metadata.isBreaking}")
                appendLine("- reasoning: ${metadata.reasoning}")
                appendLine()
            }

            appendLine("Task:")
            appendLine("Generate a Conventional Commits 1.0.0 compliant message that:")
            appendLine("1) Uses format: <type>(<scope>)${if (metadata?.isBreaking == true) "!" else ""}: <description>")
            appendLine("2) Description: imperative mood, lowercase, ≤72 chars, summarizes WHAT changed")
            appendLine("3) Body: blank line then explain WHY and provide context (wrap ~72 chars)")
            appendLine("4) Do NOT include any footer with Refs, JIRA keys, or related issues (will be added automatically)")
            if (metadata?.isBreaking == true) {
                appendLine("5) Add footer after blank line: \"BREAKING CHANGE: <description>\"")
            }
            appendLine()

            appendLine("=== STAGED FILES ===")
            stagedFiles.forEach { appendLine("- $it") }
            appendLine()

            appendLine("=== STAGED CHANGES ===")
            appendLine(smartTruncate(diff, diffBudget))
            if (diff.length > diffBudget) {
                appendLine()
                appendLine("... (diff truncated)")
            }
        }
    }

    private fun smartTruncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text

        val truncated = text.take(maxLength)
        // Try to truncate at paragraph boundary
        val lastParagraph = truncated.lastIndexOf("\n\n")
        if (lastParagraph > maxLength * 0.7) return truncated.substring(0, lastParagraph)

        // Try sentence boundary
        val lastSentence = truncated.lastIndexOf(". ")
        if (lastSentence > maxLength * 0.7) return truncated.substring(0, lastSentence + 1)

        // Try word boundary
        val lastSpace = truncated.lastIndexOf(' ')
        if (lastSpace > maxLength * 0.8) return truncated.substring(0, lastSpace)

        return truncated
    }

    private fun buildPRPrompt(
        commits: List<String>,
        diff: String,
        jiraContext: String?,
        baseBranch: String,
        currentBranch: String,
        commitAnalysis: CommitAnalysis? = null
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

            if (commitAnalysis != null) {
                appendLine(CommitAnalyzer.formatForPrompt(commitAnalysis))
            } else {
                appendLine("=== COMMITS ===")
                commits.forEach { appendLine("- $it") }
                appendLine()
            }

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
            You are a senior software engineer writing precise, concise commit messages following the Conventional Commits 1.0.0 specification.

            Conventional Commits structure:
            <type>[optional scope]: <description>
            [optional body]
            [optional footer(s)]

            REQUIRED format rules:
            1. Type: MUST be one of: feat, fix, build, chore, ci, docs, style, refactor, perf, test, revert
            2. Scope: MUST be provided in parentheses after type, e.g., feat(parser): — use the most relevant module/area
            3. Description: MUST immediately follow colon and space. Keep ≤72 chars. Use lowercase and imperative mood.
            4. Body: MAY be provided after blank line. Wrap at ~72 chars. Provide context about WHAT changed and WHY.
            5. Footer: MAY be provided after blank line. Use format: "Token: value" or "Token #value"
            6. Breaking changes: MUST use "!" after type/scope OR "BREAKING CHANGE:" footer with description

            Important:
            - NEVER fabricate requirements or files. Only use provided facts.
            - NO trailing spaces or extra blank lines at end
            - Output ONLY the commit message — no commentary, no markdown code blocks
            - If metadata suggests a type and scope, prefer those unless the diff clearly indicates otherwise
            - Do NOT include any footer with Refs, JIRA keys, or related issues — these are added automatically
        """.trimIndent()

        private val PR_SYSTEM_PROMPT = """
            You are an expert at writing clear, comprehensive PR titles and descriptions.

            Generate a PR title and description with this EXACT format. ALL 5 sections are MANDATORY.

            TITLE: <concise title under 70 characters, no prefix like "feat:" or "fix:">

            ## Summary
            A 2-4 sentence overview of what this PR does and why. Mention the primary goal, the approach taken, and any key decisions. Minimum 50 characters.

            ## What Changed
            Group changes by area using subheadings or bullet points:
            - **Area/Module**: Description of what changed
            - Be specific: mention file names, functions, or components affected
            - Group related changes together
            - Each bullet should describe the "what" and briefly the "why"
            Minimum 50 characters.

            ## Testing
            Provide actionable test steps:
            - Step-by-step instructions to verify the changes work
            - Specific commands to run (e.g., `./gradlew test`, `npm test`)
            - Edge cases or scenarios to check
            - Expected results for each test step
            Minimum 50 characters.

            ## Impact & Risks
            - What areas of the codebase are affected
            - Potential risks or side effects
            - Performance implications if any
            - Breaking changes (if applicable, describe migration steps)
            Minimum 50 characters.

            ## Additional Notes
            - Any context reviewers should know
            - Links to related documentation or design decisions
            - Follow-up tasks or known limitations
            - Related JIRA tickets (use format: [TICKET-KEY])
            Minimum 50 characters.

            Rules for the title:
            - IMPORTANT: If JIRA ticket key is provided, ALWAYS start the title with it (e.g., "BOT-123: Add user authentication")
            - Keep it under 70 characters
            - Use imperative mood (e.g., "Add user authentication" not "Added user authentication")

            CRITICAL RULES:
            - ALL 5 sections must be present and have meaningful content (no placeholder text like TODO, TBD, or N/A)
            - Each section must have at least 50 characters of real content
            - Use concrete details from the commits and diff, not generic filler
        """.trimIndent()

        fun getInstance(project: Project): AIService {
            return project.getService(AIService::class.java)
        }
    }
}
