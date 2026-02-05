package com.jirasmartcommit.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.GitResult
import com.jirasmartcommit.services.GitService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

class PRDescriptionDialog(
    private val project: Project,
    private val initialTitle: String,
    private val initialDescription: String,
    private val availableBranches: List<String>,
    private val defaultBaseBranch: String,
    private val currentBranch: String,
    private val gitService: GitService,
    private val onRegenerate: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val titleField = JBTextField(initialTitle).apply {
        columns = 60
    }

    private val baseBranchCombo = ComboBox(DefaultComboBoxModel(availableBranches.toTypedArray())).apply {
        selectedItem = defaultBaseBranch
    }

    private val descriptionArea = JBTextArea(initialDescription).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 18
        columns = 80
        font = JBUI.Fonts.create("Monospaced", 13)
    }

    private var copied = false
    private var prCreated = false

    init {
        title = "Generate PR"
        setOKButtonText("Copy to Clipboard")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 550)

        // Top section with title and base branch
        val topPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        // PR Title row
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        topPanel.add(JBLabel("PR Title:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        topPanel.add(titleField, gbc)

        // Base Branch row
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        topPanel.add(JBLabel("Base Branch:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        topPanel.add(baseBranchCombo, gbc)

        topPanel.border = JBUI.Borders.emptyBottom(12)

        // Description section
        val descriptionPanel = JPanel(BorderLayout())

        val descriptionLabel = JBLabel("PR Description:").apply {
            border = JBUI.Borders.emptyBottom(8)
        }

        val scrollPane = JBScrollPane(descriptionArea).apply {
            border = JBUI.Borders.empty()
        }

        val hintLabel = JBLabel("Markdown formatting is supported").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyTop(8)
        }

        descriptionPanel.add(descriptionLabel, BorderLayout.NORTH)
        descriptionPanel.add(scrollPane, BorderLayout.CENTER)
        descriptionPanel.add(hintLabel, BorderLayout.SOUTH)

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(descriptionPanel, BorderLayout.CENTER)

        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()

        // Create PR action (primary)
        actions.add(object : AbstractAction("Create PR") {
            override fun actionPerformed(e: ActionEvent) {
                createPullRequest()
            }
        })

        // Copy action
        actions.add(object : AbstractAction("Copy to Clipboard") {
            override fun actionPerformed(e: ActionEvent) {
                copyToClipboard()
                copied = true
                close(OK_EXIT_CODE)
            }
        })

        // Regenerate action (if callback provided)
        if (onRegenerate != null) {
            actions.add(object : AbstractAction("Regenerate") {
                override fun actionPerformed(e: ActionEvent) {
                    onRegenerate.invoke()
                }
            })
        }

        // Close action
        actions.add(cancelAction)

        return actions.toTypedArray()
    }

    private fun createPullRequest() {
        val prTitle = getPRTitle()
        val prDescription = getPRDescription()
        val targetBranch = getSelectedBaseBranch()

        if (prTitle.isBlank()) {
            showNotification("PR title cannot be empty", NotificationType.WARNING)
            return
        }

        val result = gitService.buildPullRequestUrl(
            title = prTitle,
            description = prDescription,
            sourceBranch = currentBranch,
            targetBranch = targetBranch
        )

        when (result) {
            is GitResult.Success -> {
                val urlResult = result.data

                if (urlResult.requiresManualInput) {
                    // For Bitbucket: copy title and description to clipboard
                    val clipboardContent = buildString {
                        appendLine("Title: $prTitle")
                        appendLine()
                        append(prDescription)
                    }
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val selection = StringSelection(clipboardContent)
                    clipboard.setContents(selection, selection)

                    showNotification(
                        "PR content copied to clipboard. Paste title and description in Bitbucket.",
                        NotificationType.INFORMATION
                    )
                } else {
                    showNotification("Opening PR creation page in browser...", NotificationType.INFORMATION)
                }

                BrowserUtil.browse(urlResult.url)
                prCreated = true
                close(OK_EXIT_CODE)
            }
            is GitResult.Error -> {
                showNotification(result.message, NotificationType.ERROR)
            }
        }
    }

    private fun copyToClipboard() {
        val content = buildString {
            appendLine("Title: ${titleField.text.trim()}")
            appendLine("Base Branch: ${getSelectedBaseBranch()}")
            appendLine()
            append(descriptionArea.text.trim())
        }
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(content)
        clipboard.setContents(selection, selection)
    }

    private fun showNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JIRA Smart Commit")
            .createNotification(message, type)
            .notify(project)
    }

    fun getPRTitle(): String = titleField.text.trim()

    fun getPRDescription(): String = descriptionArea.text.trim()

    fun getSelectedBaseBranch(): String = baseBranchCombo.selectedItem as? String ?: defaultBaseBranch

    fun updateContent(title: String, description: String) {
        titleField.text = title
        descriptionArea.text = description
    }

    fun wasCopied(): Boolean = copied

    fun wasPRCreated(): Boolean = prCreated
}
