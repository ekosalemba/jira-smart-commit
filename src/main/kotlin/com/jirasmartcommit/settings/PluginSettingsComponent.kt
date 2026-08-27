package com.jirasmartcommit.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.JButton
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
    private val aiModelCombo = ComboBox<String>().apply { isEditable = true }
    private val customEndpointField = JBTextField()
    private val claudeCodeHintLabel = createHintLabel(
        "Uses your local 'claude' CLI login (run 'claude login' in a terminal). No API key needed. " +
            "Leave Model blank to use the CLI's own default — alias overrides (opus/sonnet/etc.) aren't accepted on every account."
    )

    // Commit settings
    private val defaultCommitTypeCombo = ComboBox(PluginSettings.COMMIT_TYPES.toTypedArray())
    private val includeScopeCheckbox = JBCheckBox("Include scope in commit message")
    private val includeBodyCheckbox = JBCheckBox("Include body in commit message")
    private val includeFooterCheckbox = JBCheckBox("Include footer with JIRA reference")

    // Branch settings
    private val defaultBaseBranchField = JBTextField()

    // Git Platform settings
    private val gitPlatformTokenField = JBPasswordField()

    // Code Vibing settings
    private val vibeWorktreeBaseDirField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Select Worktree Base Directory",
            "Code Vibing sessions create worktrees in this directory",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
    }
    private val vibeSystemPromptArea = JBTextArea().apply {
        rows = 10
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create("Monospaced", 12)
    }
    private val vibeSystemPromptScroll = JBScrollPane(vibeSystemPromptArea).apply {
        preferredSize = Dimension(0, 180)
    }
    private val restoreDefaultPromptButton = JButton("Restore Default Template").apply {
        addActionListener {
            vibeSystemPromptArea.text = PluginSettings.DEFAULT_VIBE_SYSTEM_PROMPT
        }
    }
    private val clearPromptButton = JButton("Clear").apply {
        addActionListener {
            vibeSystemPromptArea.text = ""
        }
    }

    // Worktree settings
    private val worktreeSymlinkPathsArea = JBTextArea().apply {
        rows = 5
        lineWrap = false
        font = JBUI.Fonts.create("Monospaced", 12)
    }
    private val worktreeSymlinkPathsScroll = JBScrollPane(worktreeSymlinkPathsArea).apply {
        preferredSize = Dimension(0, 100)
    }

    init {
        // Update models when provider changes
        aiProviderCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                updateModelOptions()
                updateCredentialFieldsAvailability()
            }
        }

        // Initialize with default models
        updateModelOptions()
        updateCredentialFieldsAvailability()

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
            .addComponentToRightColumn(claudeCodeHintLabel, 0)

            // Commit Settings Section
            .addSeparator()
            .addComponent(createSectionLabel("Commit Message Settings"))
            .addLabeledComponent(JBLabel("Default Type:"), defaultCommitTypeCombo, 1, false)
            .addComponent(includeScopeCheckbox, 0)
            .addComponent(includeBodyCheckbox, 0)
            .addComponent(includeFooterCheckbox, 0)

            // Branch Settings Section
            .addSeparator()
            .addComponent(createSectionLabel("Branch Settings"))
            .addLabeledComponent(JBLabel("Default Base Branch:"), defaultBaseBranchField, 1, false)
            .addComponentToRightColumn(createHintLabel("e.g., main, master, develop"), 0)
            .addLabeledComponent(JBLabel("Symlink Paths on Worktree Create:"), worktreeSymlinkPathsScroll, 1, true)
            .addComponentToRightColumn(createHintLabel("One path per line, relative to repo root (e.g. .env, local.properties). Symlinked into every new worktree automatically."), 0)

            // Git Platform Configuration Section
            .addSeparator()
            .addComponent(createSectionLabel("Git Platform Configuration"))
            .addLabeledComponent(JBLabel("Access Token:"), gitPlatformTokenField, 1, false)
            .addComponentToRightColumn(createHintLabel("For automatic PR creation via API"), 0)
            .addComponentToRightColumn(createHintLabel("Bitbucket: Repository settings → Access tokens"), 0)
            .addComponentToRightColumn(createHintLabel("GitHub: Settings → Developer settings → Tokens"), 0)
            .addComponentToRightColumn(createHintLabel("GitLab: Preferences → Access Tokens"), 0)

            // Code Vibing Section
            .addSeparator()
            .addComponent(createSectionLabel("Code Vibing"))
            .addLabeledComponent(JBLabel("Worktree Base Dir:"), vibeWorktreeBaseDirField, 1, false)
            .addComponentToRightColumn(createHintLabel("Each session creates a worktree at <base>/<branch>. Leave empty to use <repo-parent>/<repo>-worktrees/."), 0)
            .addLabeledComponent(JBLabel("System Prompt:"), vibeSystemPromptScroll, 1, true)
            .addComponentToRightColumn(createPromptButtonsRow(), 0)
            .addComponentToRightColumn(createHintLabel("Empty = plain chat. Restore the default template for file read/write protocol."), 0)

            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun createSectionLabel(text: String): JComponent {
        val label = JBLabel(text)
        label.font = label.font.deriveFont(label.font.size2D + 2f)
        label.border = JBUI.Borders.emptyTop(10)
        return label
    }

    private fun createPromptButtonsRow(): JComponent {
        val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0))
        row.add(restoreDefaultPromptButton)
        row.add(clearPromptButton)
        return row
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
            AIProvider.CLAUDE_CODE -> PluginSettings.CLAUDE_CODE_MODELS
        }

        models.forEach { aiModelCombo.addItem(it) }
    }

    private fun updateCredentialFieldsAvailability() {
        val selectedProvider = AIProvider.fromDisplayName(aiProviderCombo.selectedItem as String)
        val usesApiKey = selectedProvider != AIProvider.CLAUDE_CODE
        aiApiKeyField.isEnabled = usesApiKey
        customEndpointField.isEnabled = usesApiKey
        claudeCodeHintLabel.isVisible = !usesApiKey
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

    // Branch settings getters/setters
    var defaultBaseBranch: String
        get() = defaultBaseBranchField.text
        set(value) { defaultBaseBranchField.text = value }

    var worktreeSymlinkPaths: String
        get() = worktreeSymlinkPathsArea.text
        set(value) { worktreeSymlinkPathsArea.text = value }

    // Git Platform settings getters/setters
    var gitPlatformToken: String
        get() = String(gitPlatformTokenField.password)
        set(value) { gitPlatformTokenField.text = value }

    // Code Vibing getters/setters
    var vibeWorktreeBaseDir: String
        get() = vibeWorktreeBaseDirField.text
        set(value) { vibeWorktreeBaseDirField.text = value }

    var vibeSystemPrompt: String
        get() = vibeSystemPromptArea.text
        set(value) { vibeSystemPromptArea.text = value }
}
