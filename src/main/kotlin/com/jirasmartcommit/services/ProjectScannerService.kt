package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

data class ProjectContext(
    val fileTree: String,
    val contextFiles: Map<String, String>,
    val projectRoot: String
)

@Service(Service.Level.PROJECT)
class ProjectScannerService(private val project: Project) {

    private val logger = Logger.getInstance(ProjectScannerService::class.java)

    private val excludedDirs = setOf(
        ".git", ".idea", ".gradle", "build", "out", "target",
        "node_modules", ".next", "dist", "__pycache__", ".venv",
        "venv", ".mypy_cache", ".pytest_cache", ".tox",
        ".eggs", "*.egg-info", ".DS_Store"
    )

    private val contextFileNames = listOf(
        "CLAUDE.md",
        ".cursorrules",
        ".github/copilot-instructions.md",
        "AGENTS.md",
        ".clinerules",
        "CONVENTIONS.md"
    )

    fun scanProject(): ProjectContext {
        val basePath = project.basePath ?: return ProjectContext("", emptyMap(), "")
        return scanProject(File(basePath))
    }

    fun scanProject(rootDir: File): ProjectContext {
        val fileTree = buildFileTree(rootDir, "", 0)
        val contextFiles = readContextFiles(rootDir)

        return ProjectContext(
            fileTree = fileTree,
            contextFiles = contextFiles,
            projectRoot = rootDir.absolutePath
        )
    }

    private fun buildFileTree(dir: File, prefix: String, depth: Int): String {
        if (depth > MAX_DEPTH) return ""

        val entries = dir.listFiles()
            ?.filter { !excludedDirs.contains(it.name) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return ""

        return buildString {
            for ((index, entry) in entries.withIndex()) {
                val isLast = index == entries.size - 1
                val connector = if (isLast) "└── " else "├── "
                val childPrefix = if (isLast) "$prefix    " else "$prefix│   "

                appendLine("$prefix$connector${entry.name}${if (entry.isDirectory) "/" else ""}")

                if (entry.isDirectory) {
                    append(buildFileTree(entry, childPrefix, depth + 1))
                }
            }
        }
    }

    private fun readContextFiles(root: File): Map<String, String> {
        val result = mutableMapOf<String, String>()

        for (fileName in contextFileNames) {
            val file = File(root, fileName)
            if (file.exists() && file.isFile) {
                try {
                    val content = file.readText()
                    if (content.length <= MAX_CONTEXT_FILE_SIZE) {
                        result[fileName] = content
                    } else {
                        result[fileName] = content.take(MAX_CONTEXT_FILE_SIZE) + "\n... (truncated)"
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to read context file: $fileName", e)
                }
            }
        }

        return result
    }

    fun readFileContents(paths: List<String>): Map<String, String> {
        val basePath = project.basePath ?: return emptyMap()
        return readFileContents(File(basePath), paths)
    }

    fun readFileContents(rootDir: File, paths: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val rootCanonical = rootDir.canonicalPath

        for (path in paths) {
            val file = File(rootDir, path)
            if (!file.exists() || !file.isFile) continue
            if (!file.canonicalPath.startsWith(rootCanonical)) continue

            try {
                val content = file.readText()
                if (content.length <= MAX_FILE_READ_SIZE) {
                    result[path] = content
                } else {
                    result[path] = content.take(MAX_FILE_READ_SIZE) + "\n... (truncated at 100KB)"
                }
            } catch (e: Exception) {
                logger.warn("Failed to read file: $path", e)
            }
        }

        return result
    }

    companion object {
        private const val MAX_DEPTH = 6
        private const val MAX_CONTEXT_FILE_SIZE = 50_000
        private const val MAX_FILE_READ_SIZE = 100_000

        fun getInstance(project: Project): ProjectScannerService {
            return project.getService(ProjectScannerService::class.java)
        }
    }
}
