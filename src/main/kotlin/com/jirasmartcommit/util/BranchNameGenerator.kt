package com.jirasmartcommit.util

object BranchNameGenerator {

    private const val MAX_SUMMARY_LENGTH = 50
    private val BRANCH_NAME_REGEX = Regex("^[a-zA-Z0-9/_.-]+$")
    private val INVALID_CHARS_REGEX = Regex("[^a-zA-Z0-9\\s-]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val MULTIPLE_HYPHENS_REGEX = Regex("-+")

    private val PREFIX_MAP = mapOf(
        "story" to "feature/",
        "feature" to "feature/",
        "bug" to "bugfix/",
        "bugfix" to "bugfix/",
        "hotfix" to "hotfix/",
        "task" to "task/",
        "sub-task" to "task/",
        "subtask" to "task/",
        "epic" to "feature/",
        "improvement" to "feature/"
    )

    private const val DEFAULT_PREFIX = "feature/"

    /**
     * Generates a branch name from JIRA ticket information.
     *
     * @param ticketKey The JIRA ticket key (e.g., "BOT-123")
     * @param summary The ticket summary/title
     * @param issueType The JIRA issue type (e.g., "Story", "Bug")
     * @return Generated branch name (e.g., "feature/BOT-123-add-user-authentication")
     */
    fun generate(ticketKey: String, summary: String, issueType: String): String {
        val prefix = getPrefix(issueType)
        val slug = slugify(summary)
        return "$prefix$ticketKey-$slug"
    }

    /**
     * Converts a text string to a URL-safe slug suitable for branch names.
     *
     * @param text The text to slugify
     * @return A lowercase, hyphen-separated slug
     */
    fun slugify(text: String): String {
        return text
            .lowercase()
            .replace(INVALID_CHARS_REGEX, "")
            .replace(WHITESPACE_REGEX, "-")
            .replace(MULTIPLE_HYPHENS_REGEX, "-")
            .trim('-')
            .take(MAX_SUMMARY_LENGTH)
            .trimEnd('-')
    }

    /**
     * Validates a git branch name according to git naming rules.
     *
     * @param branchName The branch name to validate
     * @return ValidationResult with success status and optional error message
     */
    fun validate(branchName: String): ValidationResult {
        if (branchName.isBlank()) {
            return ValidationResult(false, "Branch name cannot be empty")
        }

        if (branchName.startsWith("/") || branchName.endsWith("/")) {
            return ValidationResult(false, "Branch name cannot start or end with '/'")
        }

        if (branchName.startsWith("-") || branchName.endsWith("-")) {
            return ValidationResult(false, "Branch name cannot start or end with '-'")
        }

        if (branchName.contains("..")) {
            return ValidationResult(false, "Branch name cannot contain '..'")
        }

        if (branchName.contains("//")) {
            return ValidationResult(false, "Branch name cannot contain '//'")
        }

        if (branchName.endsWith(".lock")) {
            return ValidationResult(false, "Branch name cannot end with '.lock'")
        }

        if (branchName.contains("@{")) {
            return ValidationResult(false, "Branch name cannot contain '@{'")
        }

        if (branchName.contains("\\")) {
            return ValidationResult(false, "Branch name cannot contain '\\'")
        }

        if (!BRANCH_NAME_REGEX.matches(branchName)) {
            return ValidationResult(false, "Branch name contains invalid characters")
        }

        if (branchName.length > 200) {
            return ValidationResult(false, "Branch name is too long (max 200 characters)")
        }

        return ValidationResult(true)
    }

    /**
     * Gets the branch prefix for a given JIRA issue type.
     *
     * @param issueType The JIRA issue type
     * @return The branch prefix (e.g., "feature/", "bugfix/")
     */
    fun getPrefix(issueType: String): String {
        val normalizedType = issueType.lowercase().trim()
        return PREFIX_MAP[normalizedType] ?: DEFAULT_PREFIX
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )
}
