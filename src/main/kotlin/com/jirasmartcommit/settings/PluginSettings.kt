package com.jirasmartcommit.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AIProvider(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    CLAUDE_CODE("Claude Code CLI");

    companion object {
        fun fromDisplayName(name: String): AIProvider {
            return entries.find { it.displayName == name } ?: OPENAI
        }
    }
}

data class PluginSettingsState(
    var jiraUrl: String = "",
    var jiraEmail: String = "",
    var aiProvider: AIProvider = AIProvider.OPENAI,
    var aiModel: String = "gpt-5",
    var customEndpoint: String = "",
    var defaultCommitType: String = "feat",
    var includeScopeInCommit: Boolean = true,
    var includeBodyInCommit: Boolean = true,
    var includeFooterWithJiraRef: Boolean = true,
    var defaultBaseBranch: String = "main",
    var vibeWorktreeBaseDir: String = "",
    var vibeSystemPrompt: String = "",
    var worktreeSymlinkPaths: String = ""
)

@State(
    name = "JiraSmartCommitSettings",
    storages = [Storage("JiraSmartCommitSettings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettingsState> {

    private var settingsState = PluginSettingsState()

    override fun getState(): PluginSettingsState = settingsState

    override fun loadState(state: PluginSettingsState) {
        settingsState = state
    }

    // JIRA Settings
    var jiraUrl: String
        get() = settingsState.jiraUrl
        set(value) { settingsState.jiraUrl = value.trimEnd('/') }

    var jiraEmail: String
        get() = settingsState.jiraEmail
        set(value) { settingsState.jiraEmail = value }

    var jiraApiToken: String
        get() = getSecureCredential(JIRA_TOKEN_KEY) ?: ""
        set(value) { setSecureCredential(JIRA_TOKEN_KEY, value) }

    // AI Settings
    var aiProvider: AIProvider
        get() = settingsState.aiProvider
        set(value) { settingsState.aiProvider = value }

    var aiApiKey: String
        get() = getSecureCredential(AI_API_KEY) ?: ""
        set(value) { setSecureCredential(AI_API_KEY, value) }

    var aiModel: String
        get() = settingsState.aiModel
        set(value) { settingsState.aiModel = value }

    var customEndpoint: String
        get() = settingsState.customEndpoint
        set(value) { settingsState.customEndpoint = value }

    // Commit Settings
    var defaultCommitType: String
        get() = settingsState.defaultCommitType
        set(value) { settingsState.defaultCommitType = value }

    var includeScopeInCommit: Boolean
        get() = settingsState.includeScopeInCommit
        set(value) { settingsState.includeScopeInCommit = value }

    var includeBodyInCommit: Boolean
        get() = settingsState.includeBodyInCommit
        set(value) { settingsState.includeBodyInCommit = value }

    var includeFooterWithJiraRef: Boolean
        get() = settingsState.includeFooterWithJiraRef
        set(value) { settingsState.includeFooterWithJiraRef = value }

    // Branch Settings
    var defaultBaseBranch: String
        get() = settingsState.defaultBaseBranch
        set(value) { settingsState.defaultBaseBranch = value }

    // Code Vibing Settings
    var vibeWorktreeBaseDir: String
        get() = settingsState.vibeWorktreeBaseDir
        set(value) { settingsState.vibeWorktreeBaseDir = value }

    var vibeSystemPrompt: String
        get() = settingsState.vibeSystemPrompt
        set(value) { settingsState.vibeSystemPrompt = value }

    // Worktree Settings
    var worktreeSymlinkPaths: String
        get() = settingsState.worktreeSymlinkPaths
        set(value) { settingsState.worktreeSymlinkPaths = value }

    /** Parsed, trimmed, non-blank, non-comment paths (one per line) to symlink into new worktrees. */
    fun worktreeSymlinkPathList(): List<String> {
        return worktreeSymlinkPaths.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    // Validation
    fun isJiraConfigured(): Boolean {
        return jiraUrl.isNotBlank() && jiraEmail.isNotBlank() && jiraApiToken.isNotBlank()
    }

    fun isAIConfigured(): Boolean {
        // Claude Code CLI needs no API key here — auth is handled by the local `claude` login.
        return aiProvider == AIProvider.CLAUDE_CODE || aiApiKey.isNotBlank()
    }

    // Git Platform Settings
    var gitPlatformToken: String
        get() = getSecureCredential(GIT_PLATFORM_TOKEN_KEY) ?: ""
        set(value) { setSecureCredential(GIT_PLATFORM_TOKEN_KEY, value) }

    fun isGitPlatformConfigured(): Boolean {
        return gitPlatformToken.isNotBlank()
    }

    // Secure credential storage using PasswordSafe
    private fun getSecureCredential(key: String): String? {
        val credentialAttributes = createCredentialAttributes(key)
        return PasswordSafe.instance.getPassword(credentialAttributes)
    }

    private fun setSecureCredential(key: String, value: String) {
        val credentialAttributes = createCredentialAttributes(key)
        val credentials = Credentials("", value)
        PasswordSafe.instance.set(credentialAttributes, credentials)
    }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName(SUBSYSTEM, key)
        )
    }

    companion object {
        private const val SUBSYSTEM = "JiraSmartCommit"
        private const val JIRA_TOKEN_KEY = "jira_api_token"
        private const val AI_API_KEY = "ai_api_key"
        private const val GIT_PLATFORM_TOKEN_KEY = "git_platform_token"

        val instance: PluginSettings
            get() = ApplicationManager.getApplication().getService(PluginSettings::class.java)

        val OPENAI_MODELS = listOf(
            "gpt-5",
            "gpt-5-mini",
            "gpt-5-nano",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4o",
            "gpt-4o-mini",
            "o3",
            "o3-mini"
        )

        val ANTHROPIC_MODELS = listOf(
            "claude-opus-4-7",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001"
        )

        // Leading "" = use the CLI's own configured default model (no --model flag passed).
        // The named aliases resolve to the latest of that tier IF the account/session allows
        // overriding the model at all — some setups reject them, so default stays blank.
        val CLAUDE_CODE_MODELS = listOf(
            "",
            "opus",
            "sonnet",
            "haiku",
            "fable"
        )

        val COMMIT_TYPES = listOf(
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

        val DEFAULT_VIBE_SYSTEM_PROMPT = """
You are an expert software engineer helping implement code changes inside a JetBrains IDE.

Your workflow:
1. Analyze the project structure, context files, and JIRA ticket (if provided).
2. If you need to see existing file contents before making changes, respond ONLY with:
   REQUEST_FILES: path/to/file1, path/to/file2
3. When ready to provide changes, wrap EACH file in markers:
   ===FILE: path/to/file===
   <complete file content>
   ===END_FILE===

Rules:
- Provide COMPLETE file contents (not diffs or patches) - the file will be overwritten.
- Use paths relative to the project root.
- Follow existing project conventions (language, style, patterns).
- Explain what you're changing and why BEFORE the file blocks.
- If the task is unclear, ask clarifying questions.
- Keep changes minimal and focused on the task.
- Do not modify files that don't need changes.
        """.trimIndent()
    }
}
