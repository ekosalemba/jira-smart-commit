package com.jirasmartcommit.util

data class SectionValidation(
    val name: String,
    val exists: Boolean,
    val content: String,
    val issues: List<String>
)

data class PRValidationResult(
    val sections: List<SectionValidation>,
    val isValid: Boolean,
    val issues: List<String>
)

object PRValidator {

    private val REQUIRED_SECTIONS = listOf(
        "Summary",
        "What Changed",
        "Testing",
        "Impact & Risks",
        "Additional Notes"
    )

    private val PLACEHOLDER_PATTERNS = listOf(
        Regex("""(?i)\bTODO\b"""),
        Regex("""(?i)\bTBD\b"""),
        Regex("""(?i)^N/A$""", RegexOption.MULTILINE),
        Regex("""(?i)\bfill in\b"""),
        Regex("""(?i)\badd details\b"""),
        Regex("""(?i)\bdescribe here\b""")
    )

    fun validate(description: String): PRValidationResult {
        val allIssues = mutableListOf<String>()
        val sectionValidations = mutableListOf<SectionValidation>()

        for (sectionName in REQUIRED_SECTIONS) {
            val content = extractSection(description, sectionName)
            val sectionIssues = mutableListOf<String>()

            if (content == null) {
                sectionIssues.add("Missing section: $sectionName")
                sectionValidations.add(SectionValidation(sectionName, false, "", sectionIssues))
                allIssues.addAll(sectionIssues)
                continue
            }

            val trimmed = content.trim()

            if (trimmed.length < 50) {
                sectionIssues.add("$sectionName is too short (${trimmed.length} chars, min 50)")
            }

            for (pattern in PLACEHOLDER_PATTERNS) {
                if (pattern.containsMatchIn(trimmed)) {
                    sectionIssues.add("$sectionName contains placeholder text")
                    break
                }
            }

            // Section-specific checks
            when (sectionName) {
                "What Changed" -> {
                    if (!trimmed.contains("-") && !trimmed.contains("*") && !trimmed.contains("###")) {
                        sectionIssues.add("What Changed should use bullet points or subheadings")
                    }
                }
                "Testing" -> {
                    val hasActionableSteps = trimmed.contains("-") || trimmed.contains("1.") ||
                            trimmed.contains("*") || trimmed.contains("step", ignoreCase = true)
                    if (!hasActionableSteps) {
                        sectionIssues.add("Testing should include actionable test steps")
                    }
                }
            }

            sectionValidations.add(SectionValidation(sectionName, true, trimmed, sectionIssues))
            allIssues.addAll(sectionIssues)
        }

        return PRValidationResult(
            sections = sectionValidations,
            isValid = allIssues.isEmpty(),
            issues = allIssues
        )
    }

    private fun extractSection(description: String, sectionName: String): String? {
        // Match ## Section Name (case-insensitive, with optional &)
        val pattern = Regex(
            """(?i)##\s+${Regex.escape(sectionName).replace("\\&", "&?")}""",
        )
        val match = pattern.find(description) ?: return null

        val startIndex = match.range.last + 1
        // Find next ## heading or end of string
        val nextSection = Regex("""(?m)^##\s""").find(description, startIndex)
        val endIndex = nextSection?.range?.first ?: description.length

        return description.substring(startIndex, endIndex).trim()
    }
}
