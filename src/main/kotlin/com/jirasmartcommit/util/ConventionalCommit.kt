package com.jirasmartcommit.util

data class ConventionalCommit(
    val type: String,
    val scope: String? = null,
    val isBreaking: Boolean = false,
    val subject: String,
    val body: String? = null,
    val footer: String? = null
) {
    override fun toString(): String {
        return buildString {
            append(type)
            if (scope != null) {
                append("($scope)")
            }
            if (isBreaking) {
                append("!")
            }
            append(": ")
            append(subject)

            if (body != null) {
                append("\n\n")
                append(body)
            }

            if (footer != null) {
                append("\n\n")
                append(footer)
            }
        }
    }

    companion object {
        private val COMMIT_PATTERN = Regex(
            """^(?<type>\w+)(?:\((?<scope>[^)]+)\))?(?<breaking>!)?: (?<subject>.+?)(?:\n\n(?<body>[\s\S]*?))?(?:\n\n(?<footer>[\s\S]*))?$""",
            RegexOption.MULTILINE
        )

        private val SIMPLE_PATTERN = Regex(
            """^(\w+)(?:\(([^)]+)\))?(!)?: (.+)$"""
        )

        val VALID_TYPES = listOf(
            "feat",
            "fix",
            "docs",
            "style",
            "refactor",
            "perf",
            "test",
            "build",
            "ci",
            "chore",
            "revert"
        )

        fun parse(message: String): ConventionalCommit? {
            val lines = message.trim().split("\n\n", limit = 3)
            val headerLine = lines.firstOrNull() ?: return null

            val headerMatch = SIMPLE_PATTERN.find(headerLine) ?: return null

            val type = headerMatch.groupValues[1]
            val scope = headerMatch.groupValues[2].takeIf { it.isNotEmpty() }
            val isBreaking = headerMatch.groupValues[3] == "!"
            val subject = headerMatch.groupValues[4]

            val body = lines.getOrNull(1)?.takeIf { !it.startsWith("Refs:") && !it.startsWith("BREAKING CHANGE:") }
            val footer = lines.lastOrNull()?.takeIf { lines.size > 1 && (it.startsWith("Refs:") || it.startsWith("BREAKING CHANGE:") || lines.size > 2) }

            return ConventionalCommit(
                type = type,
                scope = scope,
                isBreaking = isBreaking,
                subject = subject,
                body = body,
                footer = if (lines.size > 2) footer else (if (lines.size == 2 && body == null) lines[1] else null)
            )
        }

        fun isValid(message: String): Boolean {
            val commit = parse(message) ?: return false
            return commit.type in VALID_TYPES && commit.subject.isNotBlank()
        }

        fun formatWithJiraRef(commit: ConventionalCommit, ticketKey: String): ConventionalCommit {
            val newFooter = if (commit.footer != null) {
                if (commit.footer.contains("Refs:") || commit.footer.contains(ticketKey)) {
                    commit.footer
                } else {
                    "${commit.footer}\nRefs: $ticketKey"
                }
            } else {
                "Refs: $ticketKey"
            }

            return commit.copy(footer = newFooter)
        }

        fun create(
            type: String,
            subject: String,
            scope: String? = null,
            body: String? = null,
            jiraTicket: String? = null,
            isBreaking: Boolean = false
        ): ConventionalCommit {
            val footer = jiraTicket?.let { "Refs: $it" }
            return ConventionalCommit(
                type = type,
                scope = scope,
                isBreaking = isBreaking,
                subject = subject,
                body = body,
                footer = footer
            )
        }
    }
}

object CommitTypeDescription {
    val descriptions = mapOf(
        "feat" to "A new feature",
        "fix" to "A bug fix",
        "docs" to "Documentation only changes",
        "style" to "Changes that do not affect the meaning of the code",
        "refactor" to "A code change that neither fixes a bug nor adds a feature",
        "perf" to "A code change that improves performance",
        "test" to "Adding missing tests or correcting existing tests",
        "build" to "Changes that affect the build system or external dependencies",
        "ci" to "Changes to CI configuration files and scripts",
        "chore" to "Other changes that don't modify src or test files",
        "revert" to "Reverts a previous commit"
    )
}
