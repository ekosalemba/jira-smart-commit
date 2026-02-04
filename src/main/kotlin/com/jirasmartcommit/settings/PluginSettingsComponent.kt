package com.jirasmartcommit.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

class PluginSettingsComponent {

    private val mainPanel: JPanel

    // JIRA fields
    private val jiraUrlField = JBTextField()
    private val jiraEmailField = JBTextField()
    private val jiraTokenField = JBPasswordField()

    // AI fields
    private val aiProviderCombo = ComboBox(AIProvider.entries.map { it.displayName }.toTypedArray())
    private val aiApiKeyField = JBPasswordField()
    private val aiModelCombo = ComboBox<String>()
    private val customEndpointField = JBTextField()

    // Commit settings
    private val defaultCommitTypeCombo = ComboBox(PluginSettings.COMMIT_TYPES.toTypedArray())
    private val includeScopeCheckbox = JBCheckBox("Include scope in commit message")
    private val includeBodyCheckbox = JBCheckBox("Include body in commit message")
    private val includeFooterCheckbox = JBCheckBox("Include footer with JIRA reference")

    init {
        // Update models when provider changes
        aiProviderCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                updateModelOptions()
            }
        }

        // Initialize with default models
        updateModelOptions()

        mainPanel = FormBuilder.createFormBuilder()
            // JIRA Configuration Section
            .addSeparator()
            .addComponent(createSectionLabel("JIRA Configuration"))
            .addLabeledComponent(JBLabel("JIRA URL:"), jiraUrlField, 1, false)
            .addComponentToRightColumn(createHintLabel("e.g., https://company.atlassian.net"), 0)
            .addLabeledComponent(JBLabel("Email:"), jiraEmailField, 1, false)
            .addLabeledComponent(JBLabel("API Token:"), jiraTokenField, 1, false)
            .addComponentToRightColumn(createHintLabel("Generate at id.atlassian.com"), 0)

            // AI Configuration Section
            .addSeparator()
            .addComponent(createSectionLabel("AI Configuration"))
            .addLabeledComponent(JBLabel("AI Provider:"), aiProviderCombo, 1, false)
            .addLabeledComponent(JBLabel("API Key:"), aiApiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), aiModelCombo, 1, false)
            .addLabeledComponent(JBLabel("Custom Endpoint:"), customEndpointField, 1, false)
            .addComponentToRightColumn(createHintLabel("Optional: Override default API endpoint"), 0)

            // Commit Settings Section
            .addSeparator()
            .addComponent(createSectionLabel("Commit Message Settings"))
            .addLabeledComponent(JBLabel("Default Type:"), defaultCommitTypeCombo, 1, false)
            .addComponent(includeScopeCheckbox, 0)
            .addComponent(includeBodyCheckbox, 0)
            .addComponent(includeFooterCheckbox, 0)

            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun createSectionLabel(text: String): JComponent {
        val label = JBLabel(text)
        label.font = label.font.deriveFont(label.font.size2D + 2f)
        label.border = JBUI.Borders.emptyTop(10)
        return label
    }

    private fun createHintLabel(text: String): JComponent {
        val label = JBLabel(text)
        label.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        label.font = label.font.deriveFont(label.font.size2D - 1f)
        return label
    }

    private fun updateModelOptions() {
        val selectedProvider = AIProvider.fromDisplayName(aiProviderCombo.selectedItem as String)
        aiModelCombo.removeAllItems()

        val models = when (selectedProvider) {
            AIProvider.OPENAI -> PluginSettings.OPENAI_MODELS
            AIProvider.ANTHROPIC -> PluginSettings.ANTHROPIC_MODELS
        }

        models.forEach { aiModelCombo.addItem(it) }
    }

    fun getPanel(): JPanel = mainPanel

    fun getPreferredFocusedComponent(): JComponent = jiraUrlField

    // JIRA getters/setters
    var jiraUrl: String
        get() = jiraUrlField.text
        set(value) { jiraUrlField.text = value }

    var jiraEmail: String
        get() = jiraEmailField.text
        set(value) { jiraEmailField.text = value }

    var jiraApiToken: String
        get() = String(jiraTokenField.password)
        set(value) { jiraTokenField.text = value }

    // AI getters/setters
    var aiProvider: AIProvider
        get() = AIProvider.fromDisplayName(aiProviderCombo.selectedItem as String)
        set(value) { aiProviderCombo.selectedItem = value.displayName }

    var aiApiKey: String
        get() = String(aiApiKeyField.password)
        set(value) { aiApiKeyField.text = value }

    var aiModel: String
        get() = aiModelCombo.selectedItem as? String ?: ""
        set(value) { aiModelCombo.selectedItem = value }

    var customEndpoint: String
        get() = customEndpointField.text
        set(value) { customEndpointField.text = value }

    // Commit settings getters/setters
    var defaultCommitType: String
        get() = defaultCommitTypeCombo.selectedItem as? String ?: "feat"
        set(value) { defaultCommitTypeCombo.selectedItem = value }

    var includeScopeInCommit: Boolean
        get() = includeScopeCheckbox.isSelected
        set(value) { includeScopeCheckbox.isSelected = value }

    var includeBodyInCommit: Boolean
        get() = includeBodyCheckbox.isSelected
        set(value) { includeBodyCheckbox.isSelected = value }

    var includeFooterWithJiraRef: Boolean
        get() = includeFooterCheckbox.isSelected
        set(value) { includeFooterCheckbox.isSelected = value }
}
