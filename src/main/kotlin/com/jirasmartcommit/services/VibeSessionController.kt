package com.jirasmartcommit.services

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.jirasmartcommit.settings.PluginSettings
import com.jirasmartcommit.util.VibeProtocol
import kotlinx.coroutines.runBlocking
import java.io.File

class VibeSessionController(
    private val project: Project,
    val session: VibeSession
) {

    private val logger = Logger.getInstance(VibeSessionController::class.java)

    private val conversationHistory = mutableListOf<ChatMessage>()

    fun getConversationHistory(): List<ChatMessage> = conversationHistory.toList()

    fun initialize(): ChatMessage {
        conversationHistory.clear()

        val scanner = ProjectScannerService.getInstance(project)
        val jiraService = JiraService.getInstance(project)
        val settings = PluginSettings.instance

        val worktreeRoot = File(session.worktreePath)
        val projectContext = scanner.scanProject(worktreeRoot)

        var jiraContext: String? = null
        if (session.ticketKey != null && settings.isJiraConfigured()) {
            val result = runBlocking { jiraService.fetchTicket(session.ticketKey) }
            if (result is JiraResult.Success) {
                jiraContext = jiraService.buildTicketContext(result.data)
            }
        }

        val userPrompt = buildInitialPrompt(projectContext, jiraContext)
        conversationHistory.add(ChatMessage(ChatRole.USER, userPrompt))

        val aiResult = callAI(userPrompt)
        return handleAIResponse(aiResult)
    }

    fun sendUserMessage(message: String): ChatMessage {
        conversationHistory.add(ChatMessage(ChatRole.USER, message))
        val aiResult = callAI(buildConversationPrompt())
        return handleAIResponse(aiResult)
    }

    private fun handleAIResponse(aiResult: AIResult<String>): ChatMessage {
        return when (aiResult) {
            is AIResult.Success -> {
                val response = aiResult.data
                conversationHistory.add(ChatMessage(ChatRole.ASSISTANT, response))

                val fileRequest = VibeProtocol.parseFileRequest(response)
                if (fileRequest != null) {
                    handleFileRequest(fileRequest)
                } else {
                    val changes = VibeProtocol.parseFileChanges(response)
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
        val worktreeRoot = File(session.worktreePath)
        val fileContents = scanner.readFileContents(worktreeRoot, requestedPaths)

        val context = buildString {
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

        conversationHistory.add(
            ChatMessage(ChatRole.SYSTEM, "Read ${fileContents.size} file(s): ${fileContents.keys.joinToString(", ")}")
        )
        conversationHistory.add(ChatMessage(ChatRole.USER, context))

        val followUp = callAI(buildConversationPrompt())
        return when (followUp) {
            is AIResult.Success -> {
                val response = followUp.data
                conversationHistory.add(ChatMessage(ChatRole.ASSISTANT, response))
                val changes = VibeProtocol.parseFileChanges(response)
                if (changes.isNotEmpty()) writeFilesToDisk(changes)
                ChatMessage(ChatRole.ASSISTANT, response)
            }
            is AIResult.Error -> {
                val errorMsg = "AI Error: ${followUp.message}"
                conversationHistory.add(ChatMessage(ChatRole.ERROR, errorMsg))
                ChatMessage(ChatRole.ERROR, errorMsg)
            }
        }
    }

    private fun callAI(userPrompt: String): AIResult<String> {
        val aiService = AIService.getInstance(project)
        val systemPrompt = PluginSettings.instance.vibeSystemPrompt
        return runBlocking {
            aiService.completeWithCustomPrompt(systemPrompt, userPrompt)
        }
    }

    private fun buildInitialPrompt(
        projectContext: ProjectContext,
        jiraContext: String?
    ): String {
        return buildString {
            appendLine("I need help implementing code changes for a project.")
            appendLine()

            if (jiraContext != null) {
                appendLine("=== JIRA TICKET ===")
                appendLine(jiraContext)
                appendLine()
            } else if (session.ticketKey != null) {
                appendLine("JIRA ticket: ${session.ticketKey} (description unavailable)")
                appendLine()
            } else {
                appendLine("No JIRA ticket attached. I'll describe what I need below.")
                appendLine()
            }

            appendLine("Working branch: ${session.branchName} (forked from ${session.baseBranch})")
            appendLine("Worktree: ${session.worktreePath}")
            appendLine()

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

            appendLine("Please analyze the ticket and project, then propose the code changes needed.")
        }
    }

    private fun buildConversationPrompt(): String {
        return buildString {
            for (msg in conversationHistory) {
                when (msg.role) {
                    ChatRole.USER -> appendLine("[USER]: ${msg.content}\n")
                    ChatRole.ASSISTANT -> appendLine("[ASSISTANT]: ${msg.content}\n")
                    ChatRole.SYSTEM -> appendLine("[SYSTEM]: ${msg.content}\n")
                    ChatRole.ERROR -> {}
                }
            }
        }
    }

    private fun writeFilesToDisk(changes: Map<String, String>): List<FileChangeResult> {
        val basePath = session.worktreePath
        val baseCanonical = File(basePath).canonicalPath
        val results = mutableListOf<FileChangeResult>()

        for ((relativePath, content) in changes) {
            try {
                val file = File(basePath, relativePath)
                if (!file.canonicalPath.startsWith(baseCanonical)) {
                    results.add(FileChangeResult(relativePath, false, "Path traversal rejected"))
                    continue
                }
                file.parentFile?.mkdirs()
                file.writeText(content)
                results.add(FileChangeResult(relativePath, true))
            } catch (e: Exception) {
                logger.error("Failed to write file: $relativePath", e)
                results.add(FileChangeResult(relativePath, false, e.message))
            }
        }

        if (results.any { it.written }) {
            try {
                WriteAction.runAndWait<Throwable> {
                    LocalFileSystem.getInstance().refresh(false)
                }
            } catch (e: Exception) {
                logger.warn("VFS refresh failed", e)
            }
        }

        val writtenCount = results.count { it.written }
        if (writtenCount > 0) {
            conversationHistory.add(
                ChatMessage(
                    ChatRole.SYSTEM,
                    "Wrote $writtenCount file(s): ${results.filter { it.written }.joinToString(", ") { it.path }}"
                )
            )
        }

        return results
    }

    companion object {
        private const val MAX_TREE_LENGTH = 10_000
    }
}
