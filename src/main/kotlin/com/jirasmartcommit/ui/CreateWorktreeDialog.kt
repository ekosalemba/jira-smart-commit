package com.jirasmartcommit.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.GitService
import com.jirasmartcommit.services.JiraTicket
import com.jirasmartcommit.util.BranchNameGenerator
import java.awt.Dimension
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class CreateWorktreeDialog(
    private val project: Project,
    private val ticket: JiraTicket,
    private val availableBranches: List<String>,
    private val generatedBranchName: String,
    private val defaultBaseBranch: String
) : DialogWrapper(project, true) {

    private val gitService = GitService.getInstance(project)

    private val ticketKeyLabel = JBLabel(ticket.key)
    private val ticketTypeLabel = JBLabel(ticket.issueType)
    private val ticketSummaryLabel = JBLabel("<html><body style='width: 400px'>${ticket.summary}</body></html>")

    private val baseBranchCombo = ComboBox(availableBranches.toTypedArray())
    private val branchNameField = JBTextField(generatedBranchName)
    private val worktreePathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            "Worktree Directory",
            "The directory where the new worktree will be created (must be empty or non-existent)",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
    }
    private val fetchBaseCheckBox = JBCheckBox("Fetch '$defaultBaseBranch' from origin before branching", true)
    private val openInNewWindowCheckBox = JBCheckBox("Open worktree in new window", true)
    private val validationLabel = JBLabel("")

    private var userEditedPath = false

    init {
        title = "Create Worktree from JIRA Ticket"
        setOKButtonText("Create Worktree")
        init()

        val defaultIndex = availableBranches.indexOf(defaultBaseBranch)
        if (defaultIndex >= 0) {
            baseBranchCombo.selectedIndex = defaultIndex
        }

        branchNameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateValidationLabel()
                recomputeWorktreePath()
            }
        })

        worktreePathField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                userEditedPath = true
            }
        })

        baseBranchCombo.addActionListener {
            fetchBaseCheckBox.text = "Fetch '${baseBranchCombo.selectedItem}' from origin before branching"
        }

        updateValidationLabel()
        recomputeWorktreePath()
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addComponent(createSectionLabel("JIRA Ticket"))
            .addLabeledComponent(JBLabel("Key:"), ticketKeyLabel, 1, false)
            .addLabeledComponent(JBLabel("Type:"), ticketTypeLabel, 1, false)
            .addLabeledComponent(JBLabel("Summary:"), ticketSummaryLabel, 1, false)

            .addSeparator()
            .addComponent(createSectionLabel("Worktree Settings"))
            .addLabeledComponent(JBLabel("Base Branch:"), baseBranchCombo, 1, false)
            .addLabeledComponent(JBLabel("Branch Name:"), branchNameField, 1, false)
            .addLabeledComponent(JBLabel("Worktree Path:"), worktreePathField, 1, false)
            .addComponent(validationLabel, 0)
            .addComponent(fetchBaseCheckBox)
            .addComponent(openInNewWindowCheckBox)

            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.preferredSize = Dimension(600, 360)
        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    private fun createSectionLabel(text: String): JComponent {
        val label = JBLabel(text)
        label.font = label.font.deriveFont(label.font.size2D + 2f)
        label.border = JBUI.Borders.emptyTop(10)
        return label
    }

    private fun recomputeWorktreePath() {
        if (userEditedPath) return
        val branch = branchNameField.text.trim()
        val computed = if (branch.isEmpty()) "" else gitService.defaultWorktreePathFor(branch)
        if (worktreePathField.text != computed) {
            worktreePathField.text = computed
            userEditedPath = false
        }
    }

    private fun updateValidationLabel() {
        val result = BranchNameGenerator.validate(branchNameField.text)
        if (result.isValid) {
            validationLabel.text = "<html><font color='green'>Valid branch name</font></html>"
        } else {
            validationLabel.text = "<html><font color='red'>${result.errorMessage}</font></html>"
        }
    }

    override fun doValidate(): ValidationInfo? {
        val branchName = branchNameField.text.trim()
        if (branchName.isBlank()) {
            return ValidationInfo("Branch name cannot be empty", branchNameField)
        }
        val result = BranchNameGenerator.validate(branchName)
        if (!result.isValid) {
            return ValidationInfo(result.errorMessage ?: "Invalid branch name", branchNameField)
        }

        val path = worktreePathField.text.trim()
        if (path.isBlank()) {
            return ValidationInfo("Worktree path is required", worktreePathField)
        }
        val pathFile = File(path)
        if (pathFile.exists() && (pathFile.listFiles()?.isNotEmpty() == true)) {
            return ValidationInfo("Worktree path must be empty or non-existent", worktreePathField)
        }
        return null
    }

    fun getBranchName(): String = branchNameField.text.trim()
    fun getBaseBranch(): String = baseBranchCombo.selectedItem as String
    fun getWorktreePath(): String = worktreePathField.text.trim()
    fun fetchBaseFirst(): Boolean = fetchBaseCheckBox.isSelected
    fun openInNewWindow(): Boolean = openInNewWindowCheckBox.isSelected
}
