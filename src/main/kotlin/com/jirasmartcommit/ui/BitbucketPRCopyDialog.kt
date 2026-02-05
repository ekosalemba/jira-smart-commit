package com.jirasmartcommit.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class BitbucketPRCopyDialog(
    private val project: Project,
    private val prTitle: String,
    private val prDescription: String,
    private val prUrl: String
) : DialogWrapper(project, true) {

    private val titleField = JBTextField(prTitle).apply {
        isEditable = false
        columns = 60
    }

    private val descriptionArea = JBTextArea(prDescription).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 15
        columns = 60
        font = JBUI.Fonts.create("Monospaced", 12)
    }

    init {
        title = "Copy PR Details"
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(650, 450)

        // Info label
        val infoLabel = JBLabel(
            "<html><b>Bitbucket doesn't support auto-fill via URL.</b><br>" +
            "Copy the title and description below, then paste them in Bitbucket.</html>"
        ).apply {
            border = JBUI.Borders.emptyBottom(16)
        }

        // Title section
        val titlePanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        titlePanel.add(JBLabel("PR Title:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        titlePanel.add(titleField, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        titlePanel.add(JButton("Copy Title").apply {
            addActionListener {
                copyToClipboard(prTitle)
            }
        }, gbc)

        // Description section
        val descPanel = JPanel(BorderLayout())

        val descHeaderPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("PR Description:"), BorderLayout.WEST)
            add(JButton("Copy Description").apply {
                addActionListener {
                    copyToClipboard(prDescription)
                }
            }, BorderLayout.EAST)
            border = JBUI.Borders.empty(12, 0, 8, 0)
        }

        val scrollPane = JBScrollPane(descriptionArea).apply {
            border = JBUI.Borders.empty()
        }

        descPanel.add(descHeaderPanel, BorderLayout.NORTH)
        descPanel.add(scrollPane, BorderLayout.CENTER)

        // Assemble main panel
        val contentPanel = JPanel(BorderLayout())
        contentPanel.add(titlePanel, BorderLayout.NORTH)
        contentPanel.add(descPanel, BorderLayout.CENTER)

        panel.add(infoLabel, BorderLayout.NORTH)
        panel.add(contentPanel, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(10)

        return panel
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()

        // Copy All action
        actions.add(object : AbstractAction("Copy All") {
            override fun actionPerformed(e: ActionEvent) {
                val content = buildString {
                    appendLine(prTitle)
                    appendLine()
                    append(prDescription)
                }
                copyToClipboard(content)
            }
        })

        // Open in Browser action (does not close dialog)
        actions.add(object : AbstractAction("Open in Browser") {
            override fun actionPerformed(e: ActionEvent) {
                BrowserUtil.browse(prUrl)
            }
        })

        // Close action
        actions.add(cancelAction)

        return actions.toTypedArray()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
    }
}
