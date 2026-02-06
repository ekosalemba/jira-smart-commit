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
    ANTHROPIC("Anthropic");

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
    var aiModel: String = "gpt-4",
    var customEndpoint: String = "",
    var defaultCommitType: String = "feat",
    var includeScopeInCommit: Boolean = true,
    var includeBodyInCommit: Boolean = true,
    var includeFooterWithJiraRef: Boolean = true,
    var defaultBaseBranch: String = "main"
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

    // Validation
    fun isJiraConfigured(): Boolean {
        return jiraUrl.isNotBlank() && jiraEmail.isNotBlank() && jiraApiToken.isNotBlank()
    }

    fun isAIConfigured(): Boolean {
        return aiApiKey.isNotBlank()
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
            "gpt-4",
            "gpt-4-turbo",
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-3.5-turbo"
        )

        val ANTHROPIC_MODELS = listOf(
            "claude-3-opus-20240229",
            "claude-3-sonnet-20240229",
            "claude-3-haiku-20240307",
            "claude-3-5-sonnet-20241022"
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
    }
}
