package com.jirasmartcommit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.JiraTicket
import com.jirasmartcommit.util.BranchNameGenerator
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class CreateBranchDialog(
    private val project: Project,
    private val ticket: JiraTicket,
    private val availableBranches: List<String>,
    private val generatedBranchName: String,
    private val defaultBaseBranch: String
) : DialogWrapper(project, true) {

    private val ticketKeyLabel = JBLabel(ticket.key)
    private val ticketTypeLabel = JBLabel(ticket.issueType)
    private val ticketSummaryLabel = JBLabel("<html><body style='width: 400px'>${ticket.summary}</body></html>")

    private val baseBranchCombo = ComboBox(availableBranches.toTypedArray())
    private val branchNameField = JBTextField(generatedBranchName)
    private val validationLabel = JBLabel("")

    init {
        title = "Create Branch from JIRA Ticket"
        setOKButtonText("Create Branch")
        init()

        // Set default base branch
        val defaultIndex = availableBranches.indexOf(defaultBaseBranch)
        if (defaultIndex >= 0) {
            baseBranchCombo.selectedIndex = defaultIndex
        }

        // Add live validation
        branchNameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateValidationLabel()
            }
        })

        updateValidationLabel()
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            // Ticket Info Section
            .addComponent(createSectionLabel("JIRA Ticket"))
            .addLabeledComponent(JBLabel("Key:"), ticketKeyLabel, 1, false)
            .addLabeledComponent(JBLabel("Type:"), ticketTypeLabel, 1, false)
            .addLabeledComponent(JBLabel("Summary:"), ticketSummaryLabel, 1, false)

            // Branch Settings Section
            .addSeparator()
            .addComponent(createSectionLabel("Branch Settings"))
            .addLabeledComponent(JBLabel("Base Branch:"), baseBranchCombo, 1, false)
            .addLabeledComponent(JBLabel("Branch Name:"), branchNameField, 1, false)
            .addComponent(validationLabel, 0)

            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel.preferredSize = Dimension(550, 280)
        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    private fun createSectionLabel(text: String): JComponent {
        val label = JBLabel(text)
        label.font = label.font.deriveFont(label.font.size2D + 2f)
        label.border = JBUI.Borders.emptyTop(10)
        return label
    }

    private fun updateValidationLabel() {
        val branchName = branchNameField.text
        val result = BranchNameGenerator.validate(branchName)

        if (result.isValid) {
            validationLabel.text = "<html><font color='green'>Valid branch name</font></html>"
        } else {
            validationLabel.text = "<html><font color='red'>${result.errorMessage}</font></html>"
        }
    }

    override fun doValidate(): ValidationInfo? {
        val branchName = branchNameField.text

        if (branchName.isBlank()) {
            return ValidationInfo("Branch name cannot be empty", branchNameField)
        }

        val result = BranchNameGenerator.validate(branchName)
        if (!result.isValid) {
            return ValidationInfo(result.errorMessage ?: "Invalid branch name", branchNameField)
        }

        return null
    }

    fun getBranchName(): String = branchNameField.text.trim()

    fun getBaseBranch(): String = baseBranchCombo.selectedItem as String
}
