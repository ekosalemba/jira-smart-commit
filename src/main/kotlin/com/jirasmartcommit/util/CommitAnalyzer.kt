package com.jirasmartcommit.util

data class ParsedCommit(
    val hash: String,
    val message: String,
    val conventional: ConventionalCommit?,
    val isBreaking: Boolean
)

data class CommitGroup(
    val type: String,
    val label: String,
    val commits: List<ParsedCommit>
)

data class CommitQualityMetrics(
    val conventionalPercent: Int,
    val scopeUsagePercent: Int,
    val avgMessageLength: Int,
    val totalCount: Int
)

data class CommitAnalysis(
    val commits: List<ParsedCommit>,
    val groups: List<CommitGroup>,
    val breakingChanges: List<ParsedCommit>,
    val metrics: CommitQualityMetrics
)

object CommitAnalyzer {

    private val TYPE_LABELS = mapOf(
        "feat" to "Features",
        "fix" to "Bug Fixes",
        "docs" to "Documentation",
        "style" to "Code Style",
        "refactor" to "Refactoring",
        "perf" to "Performance",
        "test" to "Tests",
        "build" to "Build",
        "ci" to "CI/CD",
        "chore" to "Chores",
        "revert" to "Reverts"
    )

    fun analyze(rawCommits: List<String>): CommitAnalysis {
        val parsed = rawCommits.map { parseCommitLine(it) }
        val groups = groupByType(parsed)
        val breaking = parsed.filter { it.isBreaking }
        val metrics = calculateMetrics(parsed)

        return CommitAnalysis(
            commits = parsed,
            groups = groups,
            breakingChanges = breaking,
            metrics = metrics
        )
    }

    private fun parseCommitLine(line: String): ParsedCommit {
        val trimmed = line.trim()
        // Format: "hash message" (from git log --oneline)
        val spaceIndex = trimmed.indexOf(' ')
        val hash: String
        val message: String

        if (spaceIndex > 0) {
            hash = trimmed.substring(0, spaceIndex)
            message = trimmed.substring(spaceIndex + 1)
        } else {
            hash = trimmed
            message = trimmed
        }

        val conventional = ConventionalCommit.parse(message)
        val isBreaking = conventional?.isBreaking == true ||
                message.contains("BREAKING CHANGE", ignoreCase = true)

        return ParsedCommit(
            hash = hash,
            message = message,
            conventional = conventional,
            isBreaking = isBreaking
        )
    }

    private fun groupByType(commits: List<ParsedCommit>): List<CommitGroup> {
        val typeMap = mutableMapOf<String, MutableList<ParsedCommit>>()

        for (commit in commits) {
            val type = commit.conventional?.type ?: "other"
            typeMap.getOrPut(type) { mutableListOf() }.add(commit)
        }

        // Order by VALID_TYPES first, then "other" at the end
        val ordered = ConventionalCommit.VALID_TYPES.mapNotNull { type ->
            typeMap[type]?.let { list ->
                CommitGroup(type, TYPE_LABELS[type] ?: type, list)
            }
        }

        val other = typeMap["other"]?.let {
            CommitGroup("other", "Other Changes", it)
        }

        return if (other != null) ordered + other else ordered
    }

    private fun calculateMetrics(commits: List<ParsedCommit>): CommitQualityMetrics {
        if (commits.isEmpty()) {
            return CommitQualityMetrics(0, 0, 0, 0)
        }

        val conventionalCount = commits.count { it.conventional != null }
        val scopeCount = commits.count { it.conventional?.scope != null }
        val avgLength = commits.map { it.message.length }.average().toInt()

        return CommitQualityMetrics(
            conventionalPercent = (conventionalCount * 100) / commits.size,
            scopeUsagePercent = if (conventionalCount > 0) (scopeCount * 100) / conventionalCount else 0,
            avgMessageLength = avgLength,
            totalCount = commits.size
        )
    }

    fun formatForPrompt(analysis: CommitAnalysis): String = buildString {
        appendLine("=== COMMIT ANALYSIS ===")
        appendLine("Total commits: ${analysis.metrics.totalCount}")
        appendLine("Conventional format: ${analysis.metrics.conventionalPercent}%")
        appendLine("Scope usage: ${analysis.metrics.scopeUsagePercent}%")
        appendLine()

        for (group in analysis.groups) {
            appendLine("### ${group.label} (${group.commits.size})")
            for (commit in group.commits) {
                val scope = commit.conventional?.scope?.let { "($it) " } ?: ""
                val breaking = if (commit.isBreaking) " [BREAKING]" else ""
                appendLine("- ${scope}${commit.conventional?.subject ?: commit.message}${breaking}")
            }
            appendLine()
        }

        if (analysis.breakingChanges.isNotEmpty()) {
            appendLine("### Breaking Changes")
            for (commit in analysis.breakingChanges) {
                appendLine("- ${commit.message}")
            }
        }
    }
}
