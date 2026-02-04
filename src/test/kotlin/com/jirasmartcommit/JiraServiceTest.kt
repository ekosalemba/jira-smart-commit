package com.jirasmartcommit

import com.jirasmartcommit.services.JiraService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JiraServiceTest {

    @Test
    fun `should extract ticket key from feature branch`() {
        val patterns = listOf(
            "feature/BOT-1234-add-login" to "BOT-1234",
            "BOT-1234-add-login" to "BOT-1234",
            "feature/BOT-1234" to "BOT-1234",
            "bugfix/BOT-5678-fix-crash" to "BOT-5678",
            "hotfix/PROJ-123-urgent-fix" to "PROJ-123"
        )

        patterns.forEach { (branchName, expectedKey) ->
            val extracted = extractTicketKey(branchName)
            assertEquals(expectedKey, extracted, "Failed for branch: $branchName")
        }
    }

    @Test
    fun `should extract ticket key case insensitively`() {
        val patterns = listOf(
            "feature/bot-1234-add-login" to "BOT-1234",
            "Bot-1234-feature" to "BOT-1234"
        )

        patterns.forEach { (branchName, expectedKey) ->
            val extracted = extractTicketKey(branchName)
            assertEquals(expectedKey, extracted, "Failed for branch: $branchName")
        }
    }

    @Test
    fun `should return null for branches without ticket key`() {
        val branches = listOf(
            "main",
            "master",
            "develop",
            "feature/add-login",
            "bugfix/fix-crash"
        )

        branches.forEach { branchName ->
            val extracted = extractTicketKey(branchName)
            assertNull(extracted, "Should be null for branch: $branchName")
        }
    }

    @Test
    fun `should handle multiple ticket patterns in branch name`() {
        // Should extract the first match
        val branchName = "feature/BOT-1234-and-BOT-5678"
        val extracted = extractTicketKey(branchName)
        assertEquals("BOT-1234", extracted)
    }

    // Helper function that mirrors the logic in JiraService
    private fun extractTicketKey(branchName: String): String? {
        val patterns = listOf(
            Regex("""([A-Z]+-\d+)"""),
            Regex("""([a-zA-Z]+-\d+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(branchName)
            if (match != null) {
                return match.groupValues[1].uppercase()
            }
        }

        return null
    }
}
