package com.jirasmartcommit.util

data class DiffAnalysis(
    val files: List<DiffFile>,
    val hasTestChanges: Boolean,
    val hasMigrations: Boolean,
    val deletedPublicApis: List<String>,
    val totalAdditions: Int,
    val totalDeletions: Int
)

data class DiffFile(
    val path: String,
    val status: DiffFileStatus,
    val isTest: Boolean,
    val isMigration: Boolean
)

enum class DiffFileStatus {
    ADDED, MODIFIED, DELETED, RENAMED, COPIED
}

object DiffAnalyzer {

    private val TEST_PATTERNS = listOf(
        Regex("""(?i)test[s]?/"""),
        Regex("""(?i)__tests__/"""),
        Regex("""(?i)spec[s]?/"""),
        Regex("""(?i)\.test\.\w+$"""),
        Regex("""(?i)\.spec\.\w+$"""),
        Regex("""(?i)Test\.\w+$"""),
        Regex("""(?i)_test\.\w+$""")
    )

    private val MIGRATION_PATTERNS = listOf(
        Regex("""(?i)migrations?/"""),
        Regex("""(?i)db/migrate/"""),
        Regex("""(?i)flyway/"""),
        Regex("""(?i)liquibase/"""),
        Regex("""(?i)\.sql$""")
    )

    private val PUBLIC_API_EXTENSIONS = setOf(
        "kt", "java", "ts", "js", "go", "py", "rb", "rs", "swift"
    )

    fun analyzeFromNameStatus(nameStatusOutput: List<String>, fullDiff: String? = null): DiffAnalysis {
        val files = nameStatusOutput
            .filter { it.isNotBlank() }
            .mapNotNull { parseLine(it) }

        val hasTestChanges = files.any { it.isTest }
        val hasMigrations = files.any { it.isMigration }

        val deletedPublicApis = files
            .filter { it.status == DiffFileStatus.DELETED }
            .filter { file ->
                val ext = file.path.substringAfterLast('.', "")
                ext in PUBLIC_API_EXTENSIONS
            }
            .map { it.path }

        // Count additions/deletions from full diff if available
        var additions = 0
        var deletions = 0
        fullDiff?.lines()?.forEach { line ->
            when {
                line.startsWith("+") && !line.startsWith("+++") -> additions++
                line.startsWith("-") && !line.startsWith("---") -> deletions++
            }
        }

        return DiffAnalysis(
            files = files,
            hasTestChanges = hasTestChanges,
            hasMigrations = hasMigrations,
            deletedPublicApis = deletedPublicApis,
            totalAdditions = additions,
            totalDeletions = deletions
        )
    }

    fun analyzeFromFileChanges(fileChanges: List<String>, fullDiff: String? = null): DiffAnalysis {
        val files = fileChanges.map { path ->
            val cleanPath = path.trim()
            DiffFile(
                path = cleanPath,
                status = DiffFileStatus.MODIFIED,
                isTest = isTestFile(cleanPath),
                isMigration = isMigrationFile(cleanPath)
            )
        }

        val hasTestChanges = files.any { it.isTest }
        val hasMigrations = files.any { it.isMigration }

        val deletedPublicApis = emptyList<String>()

        var additions = 0
        var deletions = 0
        fullDiff?.lines()?.forEach { line ->
            when {
                line.startsWith("+") && !line.startsWith("+++") -> additions++
                line.startsWith("-") && !line.startsWith("---") -> deletions++
            }
        }

        return DiffAnalysis(
            files = files,
            hasTestChanges = hasTestChanges,
            hasMigrations = hasMigrations,
            deletedPublicApis = deletedPublicApis,
            totalAdditions = additions,
            totalDeletions = deletions
        )
    }

    private fun parseLine(line: String): DiffFile? {
        val parts = line.trim().split("\t", limit = 2)
        if (parts.size < 2) return null

        val statusChar = parts[0].firstOrNull() ?: return null
        val path = parts.last() // Use last for renamed files (R100\told\tnew)

        val status = when (statusChar) {
            'A' -> DiffFileStatus.ADDED
            'M' -> DiffFileStatus.MODIFIED
            'D' -> DiffFileStatus.DELETED
            'R' -> DiffFileStatus.RENAMED
            'C' -> DiffFileStatus.COPIED
            else -> DiffFileStatus.MODIFIED
        }

        return DiffFile(
            path = path,
            status = status,
            isTest = isTestFile(path),
            isMigration = isMigrationFile(path)
        )
    }

    private fun isTestFile(path: String): Boolean = TEST_PATTERNS.any { it.containsMatchIn(path) }

    private fun isMigrationFile(path: String): Boolean = MIGRATION_PATTERNS.any { it.containsMatchIn(path) }
}
