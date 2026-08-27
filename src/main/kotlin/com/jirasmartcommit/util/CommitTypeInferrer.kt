package com.jirasmartcommit.util

data class CommitMetadata(
    val suggestedType: String,
    val suggestedScope: String?,
    val isBreaking: Boolean,
    val reasoning: String
)

object CommitTypeInferrer {

    // --- Type guessing from file paths ---

    fun guessType(analysis: DiffAnalysis): String {
        val files = analysis.files
        if (files.isEmpty()) return "chore"

        // Only test files → test
        if (files.all { it.isTest }) return "test"

        // Only migrations/SQL → feat
        if (files.all { it.isMigration }) return "feat"

        // Only docs
        if (files.all { isDocFile(it.path) }) return "docs"

        // Only CI files
        if (files.all { isCIFile(it.path) }) return "ci"

        // Only build files
        if (files.all { isBuildFile(it.path) }) return "build"

        // Only config files
        if (files.all { isConfigFile(it.path) }) return "chore"

        // Source code: new files → feat, deletions → refactor, modifications → fix
        val sourceFiles = files.filter { isSourceFile(it.path) }
        if (sourceFiles.isNotEmpty()) {
            val hasNewFiles = sourceFiles.any { it.status == DiffFileStatus.ADDED }
            val hasDeletedFiles = sourceFiles.any { it.status == DiffFileStatus.DELETED }
            val hasOnlyModified = sourceFiles.all { it.status == DiffFileStatus.MODIFIED }

            return when {
                hasNewFiles && !hasDeletedFiles -> "feat"
                hasDeletedFiles && !hasNewFiles -> "refactor"
                hasOnlyModified -> "fix"
                else -> "feat"
            }
        }

        return "chore"
    }

    // --- Scope inference from file paths ---

    fun inferScope(files: List<String>): String? {
        if (files.isEmpty()) return null

        // Clean Architecture layers
        val cleanArchScope = detectCleanArchScope(files)
        if (cleanArchScope != null) return cleanArchScope

        // Common directory-based scopes
        val dirScope = detectDirectoryScope(files)
        if (dirScope != null) return dirScope

        // Fallback: most common top-level directory
        val topDirs = files.mapNotNull { path ->
            val parts = path.split("/")
            // Skip src/main/kotlin etc., find meaningful directory
            val meaningful = parts.dropWhile { it in setOf("src", "main", "kotlin", "java", "test", "resources", "app", "lib") }
            meaningful.firstOrNull()
        }

        val mostCommon = topDirs.groupBy { it }.maxByOrNull { it.value.size }?.key
        return mostCommon?.lowercase()?.take(20)
    }

    // --- Breaking change detection ---

    fun detectBreaking(analysis: DiffAnalysis): Boolean {
        return analysis.hasMigrations || analysis.deletedPublicApis.isNotEmpty()
    }

    // --- Combined metadata ---

    fun infer(analysis: DiffAnalysis, filePaths: List<String>): CommitMetadata {
        val type = guessType(analysis)
        val scope = inferScope(filePaths)
        val breaking = detectBreaking(analysis)

        val reasoning = buildReasoning(type, scope, breaking, analysis)

        return CommitMetadata(
            suggestedType = type,
            suggestedScope = scope,
            isBreaking = breaking,
            reasoning = reasoning
        )
    }

    // --- Private helpers ---

    private fun detectCleanArchScope(files: List<String>): String? {
        val layers = mapOf(
            "domain" to listOf("domain/", "entity/", "entities/", "model/", "models/"),
            "usecase" to listOf("usecase/", "usecases/", "interactor/"),
            "service" to listOf("service/", "services/"),
            "controller" to listOf("controller/", "controllers/", "handler/", "handlers/"),
            "repository" to listOf("repository/", "repositories/", "dao/"),
            "ui" to listOf("ui/", "view/", "views/", "component/", "components/", "screen/", "screens/"),
            "api" to listOf("api/", "endpoint/", "endpoints/", "route/", "routes/"),
            "infra" to listOf("infrastructure/", "infra/"),
            "config" to listOf("config/", "configuration/"),
            "auth" to listOf("auth/", "authentication/", "authorization/"),
            "db" to listOf("migration/", "migrations/", "database/"),
            "util" to listOf("util/", "utils/", "helper/", "helpers/", "common/")
        )

        for ((scope, patterns) in layers) {
            if (files.any { path -> patterns.any { path.contains(it, ignoreCase = true) } }) {
                return scope
            }
        }
        return null
    }

    private fun detectDirectoryScope(files: List<String>): String? {
        // Go standard layout
        val goPatterns = mapOf(
            "cmd" to "cmd/",
            "pkg" to "pkg/",
            "internal" to "internal/"
        )
        for ((scope, pattern) in goPatterns) {
            if (files.any { it.contains(pattern) }) return scope
        }

        // Rails paths
        val railsPatterns = mapOf(
            "model" to "app/models/",
            "controller" to "app/controllers/",
            "view" to "app/views/",
            "mailer" to "app/mailers/"
        )
        for ((scope, pattern) in railsPatterns) {
            if (files.any { it.contains(pattern) }) return scope
        }

        // Actions (JetBrains specific)
        if (files.any { it.contains("actions/") }) return "action"
        if (files.any { it.contains("settings/") }) return "settings"

        return null
    }

    private fun isDocFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".rst") ||
                lower.endsWith(".adoc") || lower.contains("readme", ignoreCase = true) ||
                lower.contains("changelog", ignoreCase = true) ||
                lower.contains("docs/") || lower.contains("documentation/")
    }

    private fun isCIFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains(".github/workflows/") || lower.contains(".gitlab-ci") ||
                lower.contains("jenkinsfile") || lower.contains(".circleci/") ||
                lower.contains("bitbucket-pipelines") || lower.contains(".travis")
    }

    private fun isBuildFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts") ||
                lower.endsWith("pom.xml") || lower.endsWith("package.json") ||
                lower.endsWith("cargo.toml") || lower.endsWith("go.mod") ||
                lower.endsWith("makefile") || lower.endsWith("dockerfile") ||
                lower.contains("webpack") || lower.contains("vite.config") ||
                lower.endsWith("settings.gradle") || lower.endsWith("settings.gradle.kts")
    }

    private fun isConfigFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json") ||
                lower.endsWith(".toml") || lower.endsWith(".ini") || lower.endsWith(".env") ||
                lower.endsWith(".properties") || lower.endsWith(".xml") ||
                lower.contains("config") || lower.startsWith(".")
    }

    private fun isSourceFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "kt", "java", "ts", "tsx", "js", "jsx", "go", "py", "rb", "rs",
            "swift", "c", "cpp", "h", "cs", "php", "scala", "clj"
        )
    }

    private fun buildReasoning(type: String, scope: String?, breaking: Boolean, analysis: DiffAnalysis): String {
        val parts = mutableListOf<String>()

        val fileCount = analysis.files.size
        val added = analysis.files.count { it.status == DiffFileStatus.ADDED }
        val modified = analysis.files.count { it.status == DiffFileStatus.MODIFIED }
        val deleted = analysis.files.count { it.status == DiffFileStatus.DELETED }

        parts.add("$fileCount file(s): +$added ~$modified -$deleted")

        if (analysis.hasTestChanges) parts.add("includes tests")
        if (analysis.hasMigrations) parts.add("includes migrations")
        if (analysis.deletedPublicApis.isNotEmpty()) parts.add("deleted APIs: ${analysis.deletedPublicApis.size}")
        if (breaking) parts.add("BREAKING")

        return "Suggested $type${scope?.let { "($it)" } ?: ""}: ${parts.joinToString(", ")}"
    }
}
