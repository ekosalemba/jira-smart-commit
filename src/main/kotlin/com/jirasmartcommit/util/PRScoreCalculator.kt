package com.jirasmartcommit.util

import java.awt.Color

data class ScoreBreakdown(
    val total: Int,
    val grade: String,
    val gradeColor: Color,
    val sections: Int,
    val sectionsMax: Int,
    val jiraContext: Int,
    val jiraContextMax: Int,
    val commitQuality: Int,
    val commitQualityMax: Int,
    val testingQuality: Int,
    val testingQualityMax: Int,
    val technicalDetails: Int,
    val technicalDetailsMax: Int
) {
    fun tooltipText(): String = buildString {
        append("Sections: $sections/$sectionsMax")
        append(" | JIRA: $jiraContext/$jiraContextMax")
        append(" | Commits: $commitQuality/$commitQualityMax")
        append(" | Testing: $testingQuality/$testingQualityMax")
        append(" | Details: $technicalDetails/$technicalDetailsMax")
    }

    fun displayText(): String = "$total/100 ($grade)"
}

object PRScoreCalculator {

    fun calculate(
        validation: PRValidationResult,
        commitAnalysis: CommitAnalysis?,
        jiraContext: String?,
        description: String
    ): ScoreBreakdown {
        val sectionsScore = scoreSections(validation)
        val jiraScore = scoreJiraContext(jiraContext)
        val commitScore = scoreCommitQuality(commitAnalysis)
        val testingScore = scoreTestingQuality(validation, description)
        val technicalScore = scoreTechnicalDetails(description, commitAnalysis)

        val total = sectionsScore + jiraScore + commitScore + testingScore + technicalScore
        val grade = calculateGrade(total)
        val color = gradeColor(grade)

        return ScoreBreakdown(
            total = total,
            grade = grade,
            gradeColor = color,
            sections = sectionsScore,
            sectionsMax = 50,
            jiraContext = jiraScore,
            jiraContextMax = 20,
            commitQuality = commitScore,
            commitQualityMax = 15,
            testingQuality = testingScore,
            testingQualityMax = 10,
            technicalDetails = technicalScore,
            technicalDetailsMax = 5
        )
    }

    // 50 points: 10 per required section + bonus for >200 chars
    private fun scoreSections(validation: PRValidationResult): Int {
        var score = 0
        for (section in validation.sections) {
            if (!section.exists) continue

            // Base: 7 points for existing with content
            if (section.content.length >= 50 && section.issues.isEmpty()) {
                score += 7
            } else if (section.exists) {
                score += 3
            }

            // Bonus for substantial content (>200 chars)
            if (section.content.length > 200) {
                score += 3
            } else if (section.content.length >= 50) {
                score += 1
            }
        }
        return score.coerceAtMost(50)
    }

    // 20 points: JIRA context richness
    private fun scoreJiraContext(jiraContext: String?): Int {
        if (jiraContext == null) return 0

        var score = 0
        // Has ticket key (4 pts)
        if (jiraContext.contains(Regex("""[A-Z]+-\d+"""))) score += 4
        // Has summary (4 pts)
        if (jiraContext.contains("Summary:", ignoreCase = true)) score += 4
        // Has description (4 pts)
        if (jiraContext.contains("Description:", ignoreCase = true)) score += 4
        // Has acceptance criteria (4 pts)
        if (jiraContext.contains("Acceptance", ignoreCase = true) ||
            jiraContext.contains("Criteria", ignoreCase = true)) score += 4
        // Has issue type (4 pts)
        if (jiraContext.contains("Type:", ignoreCase = true)) score += 4

        return score.coerceAtMost(20)
    }

    // 15 points: commit quality metrics
    private fun scoreCommitQuality(analysis: CommitAnalysis?): Int {
        if (analysis == null) return 0
        val metrics = analysis.metrics
        var score = 0

        // Conventional format usage (5 pts)
        score += (metrics.conventionalPercent * 5) / 100

        // Scope usage (4 pts)
        score += (metrics.scopeUsagePercent * 4) / 100

        // Message length quality (3 pts) - ideal is 20-72 chars
        if (metrics.avgMessageLength in 20..72) score += 3
        else if (metrics.avgMessageLength in 10..100) score += 1

        // Commit count (3 pts) - some commits is better than too few/many
        score += when {
            metrics.totalCount in 2..20 -> 3
            metrics.totalCount == 1 -> 1
            metrics.totalCount > 20 -> 1
            else -> 0
        }

        return score.coerceAtMost(15)
    }

    // 10 points: testing section quality
    private fun scoreTestingQuality(validation: PRValidationResult, description: String): Int {
        val testingSection = validation.sections.find { it.name == "Testing" }
        if (testingSection == null || !testingSection.exists) return 0

        var score = 0
        val content = testingSection.content

        // Section length (3 pts)
        if (content.length > 200) score += 3
        else if (content.length >= 100) score += 2
        else if (content.length >= 50) score += 1

        // Specific test commands (4 pts)
        val testKeywords = listOf("run", "verify", "check", "test", "expect", "should", "confirm", "validate")
        val matchCount = testKeywords.count { content.contains(it, ignoreCase = true) }
        score += (matchCount * 4 / testKeywords.size).coerceAtMost(4)

        // References to test files or methods (3 pts)
        if (content.contains("test", ignoreCase = true) &&
            (content.contains(".kt") || content.contains(".java") || content.contains("spec") ||
                    content.contains("unit") || content.contains("integration"))) {
            score += 3
        } else if (content.contains("manual", ignoreCase = true) ||
            content.contains("screenshot", ignoreCase = true)) {
            score += 1
        }

        return score.coerceAtMost(10)
    }

    // 5 points: technical details
    private fun scoreTechnicalDetails(description: String, analysis: CommitAnalysis?): Int {
        var score = 0

        // File/path mentions (2 pts)
        if (description.contains(Regex("""\w+\.\w{1,5}"""))) score += 1
        if (description.contains("/") && description.contains(Regex("""\w+/\w+"""))) score += 1

        // Description length (2 pts)
        if (description.length > 1000) score += 2
        else if (description.length > 500) score += 1

        // Breaking changes documented (1 pt)
        if (analysis != null && analysis.breakingChanges.isNotEmpty()) {
            if (description.contains("breaking", ignoreCase = true)) score += 1
        } else {
            score += 1 // No breaking changes = full point
        }

        return score.coerceAtMost(5)
    }

    private fun calculateGrade(score: Int): String = when {
        score >= 85 -> "A"
        score >= 70 -> "B"
        score >= 55 -> "C"
        score >= 40 -> "D"
        else -> "F"
    }

    private fun gradeColor(grade: String): Color = when (grade) {
        "A" -> Color(0x2E, 0x7D, 0x32) // Green
        "B" -> Color(0x55, 0x8B, 0x2F) // Light green
        "C" -> Color(0xF5, 0x7F, 0x17) // Amber
        "D" -> Color(0xE6, 0x51, 0x00) // Deep orange
        else -> Color(0xC6, 0x28, 0x28) // Red
    }
}
