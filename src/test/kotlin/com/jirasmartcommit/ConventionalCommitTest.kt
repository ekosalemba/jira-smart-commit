package com.jirasmartcommit

import com.jirasmartcommit.util.ConventionalCommit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConventionalCommitTest {

    @Test
    fun `should parse simple commit message`() {
        val message = "feat: add new feature"
        val commit = ConventionalCommit.parse(message)

        assertNotNull(commit)
        assertEquals("feat", commit?.type)
        assertNull(commit?.scope)
        assertFalse(commit?.isBreaking ?: true)
        assertEquals("add new feature", commit?.subject)
    }

    @Test
    fun `should parse commit message with scope`() {
        val message = "fix(api): handle null response"
        val commit = ConventionalCommit.parse(message)

        assertNotNull(commit)
        assertEquals("fix", commit?.type)
        assertEquals("api", commit?.scope)
        assertEquals("handle null response", commit?.subject)
    }

    @Test
    fun `should parse breaking change commit`() {
        val message = "feat(auth)!: change authentication flow"
        val commit = ConventionalCommit.parse(message)

        assertNotNull(commit)
        assertEquals("feat", commit?.type)
        assertEquals("auth", commit?.scope)
        assertTrue(commit?.isBreaking ?: false)
        assertEquals("change authentication flow", commit?.subject)
    }

    @Test
    fun `should parse commit with body and footer`() {
        val message = """feat(api): add new endpoint

This adds a new endpoint for user management.
The endpoint supports CRUD operations.

Refs: BOT-1234"""

        val commit = ConventionalCommit.parse(message)

        assertNotNull(commit)
        assertEquals("feat", commit?.type)
        assertEquals("api", commit?.scope)
        assertEquals("add new endpoint", commit?.subject)
        assertNotNull(commit?.body)
        assertTrue(commit?.body?.contains("user management") ?: false)
        assertNotNull(commit?.footer)
        assertTrue(commit?.footer?.contains("BOT-1234") ?: false)
    }

    @Test
    fun `should format commit to string`() {
        val commit = ConventionalCommit(
            type = "feat",
            scope = "ui",
            isBreaking = false,
            subject = "add button component",
            body = "This adds a reusable button component.",
            footer = "Refs: BOT-123"
        )

        val expected = """feat(ui): add button component

This adds a reusable button component.

Refs: BOT-123"""

        assertEquals(expected, commit.toString())
    }

    @Test
    fun `should format breaking change commit to string`() {
        val commit = ConventionalCommit(
            type = "feat",
            scope = "api",
            isBreaking = true,
            subject = "change response format"
        )

        assertEquals("feat(api)!: change response format", commit.toString())
    }

    @Test
    fun `should validate correct commit messages`() {
        assertTrue(ConventionalCommit.isValid("feat: add feature"))
        assertTrue(ConventionalCommit.isValid("fix(api): fix bug"))
        assertTrue(ConventionalCommit.isValid("docs: update readme"))
        assertTrue(ConventionalCommit.isValid("refactor!: major refactoring"))
    }

    @Test
    fun `should invalidate incorrect commit messages`() {
        assertFalse(ConventionalCommit.isValid("invalid commit message"))
        assertFalse(ConventionalCommit.isValid("add feature")) // missing type
        assertFalse(ConventionalCommit.isValid("unknown: add feature")) // invalid type
    }

    @Test
    fun `should add JIRA reference to commit`() {
        val commit = ConventionalCommit(
            type = "feat",
            scope = "api",
            isBreaking = false,
            subject = "add endpoint"
        )

        val withRef = ConventionalCommit.formatWithJiraRef(commit, "BOT-123")

        assertEquals("Refs: BOT-123", withRef.footer)
    }

    @Test
    fun `should not duplicate JIRA reference`() {
        val commit = ConventionalCommit(
            type = "feat",
            scope = "api",
            isBreaking = false,
            subject = "add endpoint",
            footer = "Refs: BOT-123"
        )

        val withRef = ConventionalCommit.formatWithJiraRef(commit, "BOT-123")

        assertEquals("Refs: BOT-123", withRef.footer)
        assertFalse(withRef.footer!!.contains("BOT-123\nRefs: BOT-123"))
    }

    @Test
    fun `should create commit with all parameters`() {
        val commit = ConventionalCommit.create(
            type = "fix",
            subject = "resolve memory leak",
            scope = "core",
            body = "Fixed the memory leak in the cache module.",
            jiraTicket = "BOT-456"
        )

        assertEquals("fix", commit.type)
        assertEquals("core", commit.scope)
        assertEquals("resolve memory leak", commit.subject)
        assertEquals("Fixed the memory leak in the cache module.", commit.body)
        assertEquals("Refs: BOT-456", commit.footer)
    }
}
