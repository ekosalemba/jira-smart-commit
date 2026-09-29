package com.jirasmartcommit.services

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI provider that shells out to the user's locally-installed `claude` CLI in
 * non-interactive print mode, instead of calling an API with a key stored in this plugin.
 *
 * Auth is handled entirely by the CLI itself (whatever the user set up via `claude login`
 * or its own ANTHROPIC_API_KEY) — this class never reads, stores, or replays any
 * Claude Code credential. It just runs the official client as a subprocess.
 */
class ClaudeCodeProvider : AIProviderInterface {

    private val logger = Logger.getInstance(ClaudeCodeProvider::class.java)

    override suspend fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        customEndpoint: String?
    ): String {
        val binary = resolveBinary()
            ?: throw AIProviderException(
                "Claude Code CLI not found. Install it and run 'claude login', or switch AI Provider to OpenAI/Anthropic with an API key."
            )

        if (!checkAuthStatus(binary)) {
            triggerLogin(binary)
            throw AIProviderException(
                "Claude Code is not authenticated. A browser window has been opened — please complete login, then retry."
            )
        }

        val command = mutableListOf(binary, "-p", "--output-format", "text", "--allowedTools", "")
        if (model.isNotBlank()) {
            command += listOf("--model", model)
        }
        command += listOf("--system-prompt", systemPrompt)

        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw AIProviderException("Failed to launch Claude Code CLI ('$binary'): ${e.message}", e)
        }

        try {
            process.outputStream.use { it.write(userPrompt.toByteArray(Charsets.UTF_8)) }

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw AIProviderException("Claude Code CLI timed out after ${TIMEOUT_SECONDS}s")
            }

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()

            if (process.exitValue() != 0) {
                val hint = if (stderr.contains("login", ignoreCase = true) || stderr.contains("auth", ignoreCase = true)) {
                    " Run 'claude login' in a terminal."
                } else ""
                throw AIProviderException("Claude Code CLI failed: ${stderr.ifBlank { "exit code ${process.exitValue()}" }}.$hint")
            }

            if (stdout.isBlank()) {
                throw AIProviderException("Empty response from Claude Code CLI")
            }

            logger.info("Successfully generated response using Claude Code CLI, model: $model")
            return stdout
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            logger.error("Claude Code CLI call failed", e)
            throw AIProviderException("Failed to run Claude Code CLI: ${e.message}", e)
        }
    }

    /**
     * Returns true if the CLI reports the user is logged in.
     * Falls back to true (optimistic) when the status command is unavailable or
     * times out, so a broken status check never blocks a legitimately-authed user.
     */
    private fun checkAuthStatus(binary: String): Boolean {
        return try {
            val process = ProcessBuilder(binary, "auth", "status", "--json")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(AUTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn("claude auth status timed out — assuming authenticated")
                return true
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            val json = Json.parseToJsonElement(output).jsonObject
            json["loggedIn"]?.jsonPrimitive?.boolean ?: true
        } catch (e: Exception) {
            logger.warn("claude auth status check failed (${e.message}) — assuming authenticated")
            true
        }
    }

    /**
     * Launches the CLI browser-based OAuth flow in a fire-and-forget subprocess.
     * The plugin never reads or stores the resulting credential — the CLI owns it entirely.
     */
    private fun triggerLogin(binary: String) {
        try {
            ProcessBuilder(binary, "auth", "login").start()
            logger.info("Launched 'claude auth login' to open browser OAuth flow")
        } catch (e: Exception) {
            logger.warn("Failed to launch 'claude auth login': ${e.message}")
        }
    }

    /** GUI-launched IDEs often don't inherit the login shell's PATH, so probe common install locations too. */
    private fun resolveBinary(): String? {
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            val candidate = File(dir, "claude")
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        val home = System.getProperty("user.home")
        val fallbacks = listOf(
            "$home/.local/bin/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "$home/.claude/local/claude"
        )
        return fallbacks.firstOrNull { File(it).let { f -> f.isFile && f.canExecute() } }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 120L
        private const val AUTH_CHECK_TIMEOUT_SECONDS = 10L
    }
}
