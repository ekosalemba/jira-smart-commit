package com.jirasmartcommit.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.jirasmartcommit.settings.PluginSettings
import kotlinx.coroutines.runBlocking
import java.io.File

enum class ChatRole { USER, ASSISTANT, SYSTEM, ERROR }

data class ChatMessage(
    val role: ChatRole,
    val content: String
)

data class FileChangeResult(
    val path: String,
    val written: Boolean,
    val error: String? = null
)

@Service(Service.Level.PROJECT)
class CodeVibingService(private val project: Project) {

    private val logger = Logger.getInstance(CodeVibingService::class.java)

    private val conversationHistory = mutableListOf<ChatMessage>()
    private var currentTicketSummary: String? = null

    fun getTicketSummary(): String? = currentTicketSummary

    fun getConversationHistory(): List<ChatMessage> = conversationHistory.toList()

    fun resetSession() {
        conversationHistory.clear()
        currentTicketSummary = null
    }

    fun initializeSession(): ChatMessage {
        resetSession()

        val scanner = ProjectScannerService.getInstance(project)
        val gitService = GitService.getInstance(project)
        val jiraService = JiraService.getInstance(project)
        val settings = PluginSettings.instance

        // Scan project
        val projectContext = scanner.scanProject()

        // Try to fetch JIRA ticket
        var jiraContext: String? = null
        val currentBranch = gitService.getCurrentBranch()
        if (currentBranch != null && settings.isJiraConfigured()) {
            val ticketKey = jiraService.extractTicketKeyFromBranch(currentBranch)
            if (ticketKey != null) {
                val ticketResult = runBlocking { jiraService.fetchTicket(ticketKey) }
                if (ticketResult is JiraResult.Success) {
                    jiraContext = jiraService.buildTicketContext(ticketResult.data)
                    currentTicketSummary = "${ticketResult.data.key}: ${ticketResult.data.summary}"
                }
            }
        }

        // Build the initial user prompt
        val userPrompt = buildInitialPrompt(projectContext, jiraContext, currentBranch)
        conversationHistory.add(ChatMessage(ChatRole.USER, userPrompt))

        // Call AI
        val aiResult = callAI(userPrompt)

        return when (aiResult) {
            is AIResult.Success -> {
                val response = aiResult.data
                conversationHistory.add(ChatMessage(ChatRole.ASSISTANT, response))

                // Check if AI wants to read files
                val fileRequest = parseFileRequest(response)
                if (fileRequest != null) {
                    handleFileRequest(fileRequest)
                } else {
                    // Parse and write any file changes
                    val changes = parseFileChanges(response)
                    if (changes.isNotEmpty()) {
                        writeFilesToDisk(changes)
                    }
                    ChatMessage(ChatRole.ASSISTANT, response)
                }
            }
            is AIResult.Error -> {
                val errorMsg = "AI Error: ${aiResult.message}"
                conversationHistory.add(ChatMessage(ChatRole.ERROR, errorMsg))
                ChatMessage(ChatRole.ERROR, errorMsg)
            }
        }
    }

    fun sendUserMessage(message: String): ChatMessage {
        conversationHistory.add(ChatMessage(ChatRole.USER, message))

        // Build the full conversation for context
        val fullPrompt = buildConversationPrompt()

        val aiResult = callAI(fullPrompt)

        return when (aiResult) {
            is AIResult.Success -> {
                val response = aiResult.data
                conversationHistory.add(ChatMessage(ChatRole.ASSISTANT, response))

                // Check if AI wants to read files
                val fileRequest = parseFileRequest(response)
                if (fileRequest != null) {
                    handleFileRequest(fileRequest)
                } else {
                    val changes = parseFileChanges(response)
                    if (changes.isNotEmpty()) {
                        writeFilesToDisk(changes)
                    }
                    ChatMessage(ChatRole.ASSISTANT, response)
                }
            }
            is AIResult.Error -> {
                val errorMsg = "AI Error: ${aiResult.message}"
                conversationHistory.add(ChatMessage(ChatRole.ERROR, errorMsg))
                ChatMessage(ChatRole.ERROR, errorMsg)
            }
        }
    }

    private fun handleFileRequest(requestedPaths: List<String>): ChatMessage {
        val scanner = ProjectScannerService.getInstance(project)
        val fileContents = scanner.readFileContents(requestedPaths)

        val fileContextMessage = buildString {
            appendLine("Here are the requested file contents:")
            appendLine()
            for ((path, content) in fileContents) {
                appendLine("===FILE: $path===")
                appendLine(content)
                appendLine("===END_FILE===")
                appendLine()
            }
            val missing = requestedPaths.filter { it !in fileContents }
            if (missing.isNotEmpty()) {
                appendLine("Files not found: ${missing.joinToString(", ")}")
            }
            appendLine()
            appendLine("Now please generate the code changes based on these files.")
        }

        conversationHistory.add(ChatMessage(ChatRole.SYSTEM, "Read ${fileContents.size} file(s): ${fileContents.keys.joinToString(", ")}"))
        conversationHistory.add(ChatMessage(ChatRole.USER, fileContextMessage))

        val followUpResult = callAI(buildConversationPrompt())

        return when (followUpResult) {
            is AIResult.Success -> {
                val response = followUpResult.data
                conversationHistory.add(ChatMessage(ChatRole.ASSISTANT, response))

                val changes = parseFileChanges(response)
                if (changes.isNotEmpty()) {
                    writeFilesToDisk(changes)
                }
                ChatMessage(ChatRole.ASSISTANT, response)
            }
            is AIResult.Error -> {
                val errorMsg = "AI Error: ${followUpResult.message}"
                conversationHistory.add(ChatMessage(ChatRole.ERROR, errorMsg))
                ChatMessage(ChatRole.ERROR, errorMsg)
            }
        }
    }

    private fun callAI(userPrompt: String): AIResult<String> {
        val aiService = AIService.getInstance(project)
        return runBlocking {
            aiService.completeWithCustomPrompt(SYSTEM_PROMPT, userPrompt)
        }
    }

    private fun buildInitialPrompt(
        projectContext: ProjectContext,
        jiraContext: String?,
        currentBranch: String?
    ): String {
        return buildString {
            appendLine("I need help implementing code changes for a project.")
            appendLine()

            if (jiraContext != null) {
                appendLine("=== JIRA TICKET ===")
                appendLine(jiraContext)
                appendLine()
            } else {
                appendLine("No JIRA ticket context available. I'll describe what I need below.")
                appendLine()
            }

            if (currentBranch != null) {
                appendLine("Current branch: $currentBranch")
                appendLine()
            }

            appendLine("=== PROJECT STRUCTURE ===")
            appendLine(projectContext.fileTree.take(MAX_TREE_LENGTH))
            if (projectContext.fileTree.length > MAX_TREE_LENGTH) {
                appendLine("... (truncated)")
            }
            appendLine()

            if (projectContext.contextFiles.isNotEmpty()) {
                appendLine("=== PROJECT CONTEXT FILES ===")
                for ((name, content) in projectContext.contextFiles) {
                    appendLine("--- $name ---")
                    appendLine(content)
                    appendLine()
                }
            }

            appendLine("Please analyze the project and the ticket (if provided), then suggest the code changes needed.")
            appendLine("If you need to read specific files before making changes, respond with:")
            appendLine("REQUEST_FILES: path/to/file1, path/to/file2")
            appendLine()
            appendLine("When providing code changes, use this format for EACH file:")
            appendLine("===FILE: path/to/file===")
            appendLine("<complete file content>")
            appendLine("===END_FILE===")
        }
    }

    private fun buildConversationPrompt(): String {
        return buildString {
            for (msg in conversationHistory) {
                when (msg.role) {
                    ChatRole.USER -> appendLine("[USER]: ${msg.content}\n")
                    ChatRole.ASSISTANT -> appendLine("[ASSISTANT]: ${msg.content}\n")
                    ChatRole.SYSTEM -> appendLine("[SYSTEM]: ${msg.content}\n")
                    ChatRole.ERROR -> {} // skip errors in prompt
                }
            }
        }
    }

    internal fun parseFileRequest(response: String): List<String>? {
        val pattern = Regex("""REQUEST_FILES:\s*(.+)""")
        val match = pattern.find(response) ?: return null

        // Only treat as a file request if it appears early and there are no file changes
        if (response.contains("===FILE:") && response.contains("===END_FILE===")) {
            return null
        }

        return match.groupValues[1]
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    internal fun parseFileChanges(response: String): Map<String, String> {
        val changes = mutableMapOf<String, String>()
        val pattern = Regex("""===FILE:\s*(.+?)===\s*\n([\s\S]*?)===END_FILE===""")

        for (match in pattern.findAll(response)) {
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2]
            // Remove leading/trailing blank lines but preserve internal structure
            changes[path] = content.trimEnd('\n') + "\n"
        }

        return changes
    }

    private fun writeFilesToDisk(changes: Map<String, String>): List<FileChangeResult> {
        val basePath = project.basePath ?: return emptyList()
        val results = mutableListOf<FileChangeResult>()

        for ((relativePath, content) in changes) {
            try {
                val file = File(basePath, relativePath)

                // Prevent path traversal
                if (!file.canonicalPath.startsWith(File(basePath).canonicalPath)) {
                    results.add(FileChangeResult(relativePath, false, "Path traversal rejected"))
                    continue
                }

                // Create parent directories
                file.parentFile?.mkdirs()

                // Write file
                file.writeText(content)

                results.add(FileChangeResult(relativePath, true))
                logger.info("Wrote file: $relativePath")
            } catch (e: Exception) {
                logger.error("Failed to write file: $relativePath", e)
                results.add(FileChangeResult(relativePath, false, e.message))
            }
        }

        // Refresh VFS so the IDE picks up changes
        if (results.any { it.written }) {
            try {
                WriteAction.runAndWait<Throwable> {
                    LocalFileSystem.getInstance().refresh(false)
                }
            } catch (e: Exception) {
                logger.warn("VFS refresh failed", e)
            }
        }

        val written = results.count { it.written }
        if (written > 0) {
            conversationHistory.add(
                ChatMessage(ChatRole.SYSTEM, "Wrote $written file(s): ${results.filter { it.written }.joinToString(", ") { it.path }}")
            )
        }

        return results
    }

    companion object {
        private const val MAX_TREE_LENGTH = 10_000

        private val SYSTEM_PROMPT = """
You are an expert software engineer helping implement code changes inside a JetBrains IDE.

Your workflow:
1. Analyze the project structure, context files, and JIRA ticket (if provided).
2. If you need to see existing file contents before making changes, respond ONLY with:
   REQUEST_FILES: path/to/file1, path/to/file2
3. When ready to provide changes, wrap EACH file in markers:
   ===FILE: path/to/file===
   <complete file content>
   ===END_FILE===

Rules:
- Provide COMPLETE file contents (not diffs or patches) - the file will be overwritten.
- Use paths relative to the project root.
- Follow existing project conventions (language, style, patterns).
- Explain what you're changing and why BEFORE the file blocks.
- If the task is unclear, ask clarifying questions.
- Keep changes minimal and focused on the task.
- Do not modify files that don't need changes.
        """.trimIndent()

        fun getInstance(project: Project): CodeVibingService {
            return project.getService(CodeVibingService::class.java)
        }
    }
}
