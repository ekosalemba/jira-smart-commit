package com.jirasmartcommit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.util.ConventionalCommit
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class CommitMessageDialog(
    private val project: Project,
    private val initialMessage: String,
    private val currentBranch: String? = null,
    private val hasRemote: Boolean = false,
    private val onRegenerate: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val messageArea = JBTextArea(initialMessage).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 10
        columns = 60
        font = JBUI.Fonts.create("Monospaced", 13)
    }

    private var commitAction: Boolean = false
    private var pushAfterCommit: Boolean = false
    private var pushOnlyAction: Boolean = false

    init {
        title = "Generated Commit Message"
        setOKButtonText("Commit")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(600, 380)

        // Header with branch info
        val headerPanel = JPanel(BorderLayout())

        val editLabel = JBLabel("Edit the generated commit message:").apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        headerPanel.add(editLabel, BorderLayout.NORTH)

        // Branch info display
        if (currentBranch != null) {
            val branchPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            val branchIcon = JBLabel("\uD83D\uDD00") // 🔀 branch icon
            val branchLabel = JBLabel(" Branch: ").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
            val branchNameLabel = JBLabel("<html><b>$currentBranch</b></html>").apply {
                foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            }
            branchPanel.add(branchIcon)
            branchPanel.add(branchLabel)
            branchPanel.add(branchNameLabel)
            branchPanel.border = JBUI.Borders.emptyBottom(8)
            headerPanel.add(branchPanel, BorderLayout.SOUTH)
        }

        val scrollPane = JBScrollPane(messageArea).apply {
            border = JBUI.Borders.empty()
        }

        val hintLabel = JBLabel("Use conventional commit format: type(scope): subject").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = font.deriveFont(font.size2D - 1f)
            border = JBUI.Borders.emptyTop(8)
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(hintLabel, BorderLayout.SOUTH)

        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()

        // Commit & Push action (only if remote exists)
        if (hasRemote && currentBranch != null) {
            actions.add(object : AbstractAction("Commit & Push") {
                override fun actionPerformed(e: ActionEvent) {
                    commitAction = true
                    pushAfterCommit = true
                    doOKAction()
                }
            })
        }

        // Commit action
        actions.add(object : AbstractAction("Commit") {
            override fun actionPerformed(e: ActionEvent) {
                commitAction = true
                pushAfterCommit = false
                doOKAction()
            }
        })

        // Push only action (only if remote exists)
        if (hasRemote && currentBranch != null) {
            actions.add(object : AbstractAction("Push") {
                override fun actionPerformed(e: ActionEvent) {
                    pushOnlyAction = true
                    doOKAction()
                }
            })
        }

        // Regenerate action (if callback provided)
        if (onRegenerate != null) {
            actions.add(object : AbstractAction("Regenerate") {
                override fun actionPerformed(e: ActionEvent) {
                    onRegenerate.invoke()
                }
            })
        }

        // Copy action
        actions.add(object : AbstractAction("Copy") {
            override fun actionPerformed(e: ActionEvent) {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                val selection = java.awt.datatransfer.StringSelection(messageArea.text)
                clipboard.setContents(selection, selection)
            }
        })

        // Cancel action
        actions.add(cancelAction)

        return actions.toTypedArray()
    }

    override fun doValidate(): ValidationInfo? {
        // Skip validation if only pushing (no commit message needed)
        if (pushOnlyAction) {
            return null
        }

        val message = messageArea.text.trim()

        if (message.isBlank()) {
            return ValidationInfo("Commit message cannot be empty", messageArea)
        }

        val firstLine = message.lines().first()
        if (firstLine.length > 72) {
            return ValidationInfo("First line should be 72 characters or less (currently ${firstLine.length})", messageArea)
        }

        if (!ConventionalCommit.isValid(message)) {
            // Warning only - don't block commit
            return null
        }

        return null
    }

    fun getCommitMessage(): String = messageArea.text.trim()

    fun updateMessage(newMessage: String) {
        messageArea.text = newMessage
    }

    fun shouldCommit(): Boolean = commitAction

    fun shouldPush(): Boolean = pushAfterCommit

    fun shouldPushOnly(): Boolean = pushOnlyAction
}
