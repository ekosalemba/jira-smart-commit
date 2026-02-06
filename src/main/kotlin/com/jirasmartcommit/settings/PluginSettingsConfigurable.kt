package com.jirasmartcommit.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class PluginSettingsConfigurable : Configurable {

    private var settingsComponent: PluginSettingsComponent? = null

    override fun getDisplayName(): String = "JIRA Smart Commit"

    override fun getPreferredFocusedComponent(): JComponent? {
        return settingsComponent?.getPreferredFocusedComponent()
    }

    override fun createComponent(): JComponent? {
        settingsComponent = PluginSettingsComponent()
        return settingsComponent?.getPanel()
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.instance
        val component = settingsComponent ?: return false

        return component.jiraUrl != settings.jiraUrl ||
                component.jiraEmail != settings.jiraEmail ||
                component.jiraApiToken != settings.jiraApiToken ||
                component.aiProvider != settings.aiProvider ||
                component.aiApiKey != settings.aiApiKey ||
                component.aiModel != settings.aiModel ||
                component.customEndpoint != settings.customEndpoint ||
                component.defaultCommitType != settings.defaultCommitType ||
                component.includeScopeInCommit != settings.includeScopeInCommit ||
                component.includeBodyInCommit != settings.includeBodyInCommit ||
                component.includeFooterWithJiraRef != settings.includeFooterWithJiraRef ||
                component.defaultBaseBranch != settings.defaultBaseBranch ||
                component.gitPlatformToken != settings.gitPlatformToken
    }

    override fun apply() {
        val settings = PluginSettings.instance
        val component = settingsComponent ?: return

        settings.jiraUrl = component.jiraUrl
        settings.jiraEmail = component.jiraEmail
        settings.jiraApiToken = component.jiraApiToken
        settings.aiProvider = component.aiProvider
        settings.aiApiKey = component.aiApiKey
        settings.aiModel = component.aiModel
        settings.customEndpoint = component.customEndpoint
        settings.defaultCommitType = component.defaultCommitType
        settings.includeScopeInCommit = component.includeScopeInCommit
        settings.includeBodyInCommit = component.includeBodyInCommit
        settings.includeFooterWithJiraRef = component.includeFooterWithJiraRef
        settings.defaultBaseBranch = component.defaultBaseBranch
        settings.gitPlatformToken = component.gitPlatformToken
    }

    override fun reset() {
        val settings = PluginSettings.instance
        val component = settingsComponent ?: return

        component.jiraUrl = settings.jiraUrl
        component.jiraEmail = settings.jiraEmail
        component.jiraApiToken = settings.jiraApiToken
        component.aiProvider = settings.aiProvider
        component.aiApiKey = settings.aiApiKey
        component.aiModel = settings.aiModel
        component.customEndpoint = settings.customEndpoint
        component.defaultCommitType = settings.defaultCommitType
        component.includeScopeInCommit = settings.includeScopeInCommit
        component.includeBodyInCommit = settings.includeBodyInCommit
        component.includeFooterWithJiraRef = settings.includeFooterWithJiraRef
        component.defaultBaseBranch = settings.defaultBaseBranch
        component.gitPlatformToken = settings.gitPlatformToken
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}
