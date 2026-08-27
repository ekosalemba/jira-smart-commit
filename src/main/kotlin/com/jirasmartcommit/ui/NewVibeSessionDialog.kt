package com.jirasmartcommit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.GitService
import com.jirasmartcommit.services.JiraResult
import com.jirasmartcommit.services.JiraService
import com.jirasmartcommit.services.JiraTicket
import com.jirasmartcommit.settings.PluginSettings
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class NewVibeSessionDialog(private val project: Project) : DialogWrapper(project, true) {

    private val gitService = GitService.getInstance(project)
    private val jiraService = JiraService.getInstance(project)
    private val settings = PluginSettings.instance

    private val ticketKeyField = JBTextField().apply { columns = 16 }
    private val fetchButton = JButton("Fetch")
    private val ticketPreview = JBTextArea().apply {
        rows = 4
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        background = null
    }
    private val ticketStatusLabel = JBLabel("Enter a ticket key (e.g. ABC-123) and click Fetch, or leave empty.")

    private val baseBranchCombo: ComboBox<String>
    private val branchNameField = JBTextField().apply { columns = 32 }
    private val worktreePathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Worktree Directory",
            "The directory where the new worktree will be created (must be empty or non-existent)",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
    }
    private val fetchBaseCheckBox = JBCheckBox("Fetch '<base>' from origin before branching", true)

    private var fetchedTicket: JiraTicket? = null
    private var userEditedBranchName = false

    init {
        title = "New Code Vibing Session"
        setOKButtonText("Create Session")
        val branches = gitService.getAvailableBaseBranches()
        baseBranchCombo = ComboBox(branches.toTypedArray()).apply {
            selectedItem = branches.firstOrNull() ?: settings.defaultBaseBranch
        }
        init()
        wireListeners()
        recomputeBranchName()
        recomputeWorktreePath()
    }

    private fun wireListeners() {
        fetchButton.addActionListener { fetchTicket() }

        ticketKeyField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                fetchedTicket = null
                ticketStatusLabel.text = if (ticketKeyField.text.isBlank()) {
                    "Leave empty for a freeform session."
                } else {
                    "Click Fetch to validate the ticket."
                }
                ticketPreview.text = ""
                recomputeBranchName()
            }
        })

        baseBranchCombo.addActionListener { recomputeBranchName() }

        branchNameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                userEditedBranchName = true
                recomputeWorktreePath()
            }
        })

        fetchBaseCheckBox.text = "Fetch '${baseBranchCombo.selectedItem}' from origin before branching"
        baseBranchCombo.addActionListener {
            fetchBaseCheckBox.text = "Fetch '${baseBranchCombo.selectedItem}' from origin before branching"
        }
    }

    private fun recomputeBranchName() {
        if (userEditedBranchName) return
        val key = ticketKeyField.text.trim().uppercase()
        val summarySlug = fetchedTicket?.summary?.let { kebab(it).take(40) }
        val suggested = when {
            key.isNotBlank() && summarySlug != null -> "feature/$key-$summarySlug"
            key.isNotBlank() -> "feature/$key"
            else -> ""
        }
        if (branchNameField.text != suggested) {
            branchNameField.text = suggested
            userEditedBranchName = false
        }
    }

    private fun recomputeWorktreePath() {
        val branch = branchNameField.text.trim()
        if (branch.isEmpty()) {
            worktreePathField.text = ""
            return
        }
        worktreePathField.text = gitService.defaultWorktreePathFor(branch)
    }

    private fun fetchTicket() {
        val key = ticketKeyField.text.trim().uppercase()
        if (key.isBlank()) {
            ticketStatusLabel.text = "No ticket key entered."
            return
        }
        if (!settings.isJiraConfigured()) {
            ticketStatusLabel.text = "JIRA is not configured (Settings → Tools → JIRA Smart Commit)."
            return
        }

        ticketStatusLabel.text = "Fetching $key..."
        fetchButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching $key...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = runBlocking { jiraService.fetchTicket(key) }
                ApplicationManager.getApplication().invokeLater {
                    fetchButton.isEnabled = true
                    when (result) {
                        is JiraResult.Success -> {
                            fetchedTicket = result.data
                            ticketKeyField.text = result.data.key
                            ticketStatusLabel.text = "Fetched ${result.data.key} (${result.data.status})"
                            ticketPreview.text = buildString {
                                appendLine(result.data.summary)
                                val desc = result.data.description
                                if (!desc.isNullOrBlank()) {
                                    appendLine()
                                    appendLine(desc.take(800))
                                }
                            }.trim()
                            ticketPreview.caretPosition = 0
                            userEditedBranchName = false
                            recomputeBranchName()
                            recomputeWorktreePath()
                        }
                        is JiraResult.Error -> {
                            fetchedTicket = null
                            ticketStatusLabel.text = "Fetch failed: ${result.message}"
                            ticketPreview.text = ""
                        }
                    }
                }
            }
        })
    }

    private fun kebab(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(GridBagLayout())
        root.preferredSize = Dimension(640, 480)
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        var row = 0

        // Ticket key + fetch
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        root.add(JBLabel("JIRA Ticket Key:"), gbc)

        val ticketPanel = JPanel(BorderLayout(4, 0))
        ticketPanel.add(ticketKeyField, BorderLayout.CENTER)
        ticketPanel.add(fetchButton, BorderLayout.EAST)
        gbc.gridx = 1; gbc.weightx = 1.0
        root.add(ticketPanel, gbc)

        row++
        gbc.gridx = 1; gbc.gridy = row
        ticketStatusLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        ticketStatusLabel.font = ticketStatusLabel.font.deriveFont(ticketStatusLabel.font.size2D - 1f)
        root.add(ticketStatusLabel, gbc)

        row++
        gbc.gridx = 1; gbc.gridy = row; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.4
        root.add(JBScrollPane(ticketPreview).apply { preferredSize = Dimension(0, 120) }, gbc)
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weighty = 0.0

        // Base branch
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        root.add(JBLabel("Base Branch:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        root.add(baseBranchCombo, gbc)

        // Branch name
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        root.add(JBLabel("New Branch Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        root.add(branchNameField, gbc)

        // Worktree path
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        root.add(JBLabel("Worktree Path:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        root.add(worktreePathField, gbc)

        // Fetch checkbox
        row++
        gbc.gridx = 1; gbc.gridy = row
        root.add(fetchBaseCheckBox, gbc)

        // Spacer
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        root.add(JPanel(), gbc)

        root.border = JBUI.Borders.empty(8)
        return root
    }

    override fun doValidate(): ValidationInfo? {
        val branch = branchNameField.text.trim()
        if (branch.isBlank()) {
            return ValidationInfo("Branch name is required", branchNameField)
        }
        val path = worktreePathField.text.trim()
        if (path.isBlank()) {
            return ValidationInfo("Worktree path is required", worktreePathField)
        }
        val pathFile = java.io.File(path)
        if (pathFile.exists() && (pathFile.listFiles()?.isNotEmpty() == true)) {
            return ValidationInfo("Worktree path must be empty or non-existent", worktreePathField)
        }
        return null
    }

    fun ticketKey(): String? = ticketKeyField.text.trim().takeIf { it.isNotBlank() }?.uppercase()
    fun ticketSummary(): String? = fetchedTicket?.summary
    fun baseBranch(): String = baseBranchCombo.selectedItem as? String ?: settings.defaultBaseBranch
    fun branchName(): String = branchNameField.text.trim()
    fun worktreePath(): String = worktreePathField.text.trim()
    fun fetchBaseFirst(): Boolean = fetchBaseCheckBox.isSelected
    fun createBranchFlag(): Boolean = !gitService.branchExistsAnywhere(branchName())
}
