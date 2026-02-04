package com.jirasmartcommit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class PRDescriptionDialog(
    private val project: Project,
    private val initialDescription: String,
    private val onRegenerate: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val descriptionArea = JBTextArea(initialDescription).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 20
        columns = 80
        font = JBUI.Fonts.create("Monospaced", 13)
    }

    private var copied = false

    init {
        title = "Generated PR Description"
        setOKButtonText("Copy to Clipboard")
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 500)

        val headerLabel = JBLabel("Edit the generated PR description:").apply {
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

        panel.add(headerLabel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(hintLabel, BorderLayout.SOUTH)

        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()

        // Copy action (OK)
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

    private fun copyToClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(descriptionArea.text)
        clipboard.setContents(selection, selection)
    }

    fun getDescription(): String = descriptionArea.text.trim()

    fun updateDescription(newDescription: String) {
        descriptionArea.text = newDescription
    }

    fun wasCopied(): Boolean = copied
}
