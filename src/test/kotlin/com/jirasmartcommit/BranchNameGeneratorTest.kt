package com.jirasmartcommit

import com.jirasmartcommit.util.BranchNameGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BranchNameGeneratorTest {

    @Test
    fun `should generate branch name from ticket info`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-123",
            summary = "Add user authentication",
            issueType = "Story"
        )

        assertEquals("feature/BOT-123-add-user-authentication", branchName)
    }

    @Test
    fun `should generate bugfix branch for Bug issue type`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-456",
            summary = "Fix login redirect issue",
            issueType = "Bug"
        )

        assertEquals("bugfix/BOT-456-fix-login-redirect-issue", branchName)
    }

    @Test
    fun `should generate hotfix branch for Hotfix issue type`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-789",
            summary = "Critical security patch",
            issueType = "Hotfix"
        )

        assertEquals("hotfix/BOT-789-critical-security-patch", branchName)
    }

    @Test
    fun `should generate task branch for Task issue type`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-101",
            summary = "Update dependencies",
            issueType = "Task"
        )

        assertEquals("task/BOT-101-update-dependencies", branchName)
    }

    @Test
    fun `should generate task branch for Sub-task issue type`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-102",
            summary = "Implement API endpoint",
            issueType = "Sub-task"
        )

        assertEquals("task/BOT-102-implement-api-endpoint", branchName)
    }

    @Test
    fun `should use default prefix for unknown issue type`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-999",
            summary = "Some work item",
            issueType = "Unknown Type"
        )

        assertEquals("feature/BOT-999-some-work-item", branchName)
    }

    // Slugify tests

    @Test
    fun `should convert to lowercase`() {
        val slug = BranchNameGenerator.slugify("Hello World")
        assertEquals("hello-world", slug)
    }

    @Test
    fun `should replace spaces with hyphens`() {
        val slug = BranchNameGenerator.slugify("multiple   spaces   here")
        assertEquals("multiple-spaces-here", slug)
    }

    @Test
    fun `should remove special characters`() {
        val slug = BranchNameGenerator.slugify("Add feature (with tests)!")
        assertEquals("add-feature-with-tests", slug)
    }

    @Test
    fun `should handle apostrophes and quotes`() {
        val slug = BranchNameGenerator.slugify("Don't break user's data")
        assertEquals("dont-break-users-data", slug)
    }

    @Test
    fun `should remove leading and trailing hyphens`() {
        val slug = BranchNameGenerator.slugify("  -some text-  ")
        assertEquals("some-text", slug)
    }

    @Test
    fun `should collapse multiple hyphens`() {
        val slug = BranchNameGenerator.slugify("text---with---hyphens")
        assertEquals("text-with-hyphens", slug)
    }

    @Test
    fun `should truncate long summaries`() {
        val longSummary = "This is a very long summary that should be truncated because it exceeds the maximum allowed length for branch names"
        val slug = BranchNameGenerator.slugify(longSummary)

        assertTrue(slug.length <= 50)
        assertFalse(slug.endsWith("-"))
    }

    @Test
    fun `should handle unicode characters`() {
        val slug = BranchNameGenerator.slugify("Add support for émojis 🚀")
        assertEquals("add-support-for-mojis", slug)
    }

    // Validation tests

    @Test
    fun `should validate correct branch names`() {
        assertTrue(BranchNameGenerator.validate("feature/BOT-123-add-feature").isValid)
        assertTrue(BranchNameGenerator.validate("bugfix/FIX-456-fix-bug").isValid)
        assertTrue(BranchNameGenerator.validate("main").isValid)
        assertTrue(BranchNameGenerator.validate("develop").isValid)
        assertTrue(BranchNameGenerator.validate("release/v1.0.0").isValid)
    }

    @Test
    fun `should reject empty branch name`() {
        val result = BranchNameGenerator.validate("")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot be empty", result.errorMessage)
    }

    @Test
    fun `should reject branch name starting with slash`() {
        val result = BranchNameGenerator.validate("/feature/test")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot start or end with '/'", result.errorMessage)
    }

    @Test
    fun `should reject branch name ending with slash`() {
        val result = BranchNameGenerator.validate("feature/test/")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot start or end with '/'", result.errorMessage)
    }

    @Test
    fun `should reject branch name with double dots`() {
        val result = BranchNameGenerator.validate("feature..test")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot contain '..'", result.errorMessage)
    }

    @Test
    fun `should reject branch name with double slashes`() {
        val result = BranchNameGenerator.validate("feature//test")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot contain '//'", result.errorMessage)
    }

    @Test
    fun `should reject branch name ending with dot lock`() {
        val result = BranchNameGenerator.validate("feature/test.lock")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot end with '.lock'", result.errorMessage)
    }

    @Test
    fun `should reject branch name with at brace`() {
        val result = BranchNameGenerator.validate("feature@{test}")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot contain '@{'", result.errorMessage)
    }

    @Test
    fun `should reject branch name with backslash`() {
        val result = BranchNameGenerator.validate("feature\\test")
        assertFalse(result.isValid)
        assertEquals("Branch name cannot contain '\\'", result.errorMessage)
    }

    @Test
    fun `should reject branch name with invalid characters`() {
        val result = BranchNameGenerator.validate("feature/test branch")
        assertFalse(result.isValid)
        assertEquals("Branch name contains invalid characters", result.errorMessage)
    }

    @Test
    fun `should reject very long branch names`() {
        val longName = "a".repeat(201)
        val result = BranchNameGenerator.validate(longName)
        assertFalse(result.isValid)
        assertEquals("Branch name is too long (max 200 characters)", result.errorMessage)
    }

    // Prefix mapping tests

    @Test
    fun `should return feature prefix for Story`() {
        assertEquals("feature/", BranchNameGenerator.getPrefix("Story"))
    }

    @Test
    fun `should return feature prefix for Feature`() {
        assertEquals("feature/", BranchNameGenerator.getPrefix("Feature"))
    }

    @Test
    fun `should return bugfix prefix for Bug`() {
        assertEquals("bugfix/", BranchNameGenerator.getPrefix("Bug"))
    }

    @Test
    fun `should return hotfix prefix for Hotfix`() {
        assertEquals("hotfix/", BranchNameGenerator.getPrefix("Hotfix"))
    }

    @Test
    fun `should return task prefix for Task`() {
        assertEquals("task/", BranchNameGenerator.getPrefix("Task"))
    }

    @Test
    fun `should be case insensitive for issue types`() {
        assertEquals("feature/", BranchNameGenerator.getPrefix("STORY"))
        assertEquals("bugfix/", BranchNameGenerator.getPrefix("BUG"))
        assertEquals("hotfix/", BranchNameGenerator.getPrefix("HOTFIX"))
        assertEquals("task/", BranchNameGenerator.getPrefix("TASK"))
    }

    @Test
    fun `should return default prefix for unknown types`() {
        assertEquals("feature/", BranchNameGenerator.getPrefix("Custom Type"))
        assertEquals("feature/", BranchNameGenerator.getPrefix(""))
        assertEquals("feature/", BranchNameGenerator.getPrefix("Random"))
    }

    // Integration tests

    @Test
    fun `generated branch name should pass validation`() {
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-123",
            summary = "Add complex feature with (special) chars!",
            issueType = "Story"
        )

        val result = BranchNameGenerator.validate(branchName)
        assertTrue(result.isValid, "Generated branch name should be valid: $branchName")
    }

    @Test
    fun `should handle edge case with very long summary`() {
        val longSummary = "A".repeat(200)
        val branchName = BranchNameGenerator.generate(
            ticketKey = "BOT-123",
            summary = longSummary,
            issueType = "Story"
        )

        val result = BranchNameGenerator.validate(branchName)
        assertTrue(result.isValid, "Generated branch name should be valid even with long summary")
    }
}
