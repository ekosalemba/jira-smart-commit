package com.jirasmartcommit.util

object VibeProtocol {

    private val FILE_REQUEST_PATTERN = Regex("""REQUEST_FILES:\s*(.+)""")
    private val FILE_BLOCK_PATTERN = Regex("""===FILE:\s*(.+?)===\s*\n([\s\S]*?)===END_FILE===""")

    fun parseFileRequest(response: String): List<String>? {
        val match = FILE_REQUEST_PATTERN.find(response) ?: return null
        if (response.contains("===FILE:") && response.contains("===END_FILE===")) return null
        return match.groupValues[1]
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun parseFileChanges(response: String): Map<String, String> {
        val changes = mutableMapOf<String, String>()
        for (match in FILE_BLOCK_PATTERN.findAll(response)) {
            val path = match.groupValues[1].trim()
            val content = match.groupValues[2]
            changes[path] = content.trimEnd('\n') + "\n"
        }
        return changes
    }
}
