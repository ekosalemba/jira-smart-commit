package com.jirasmartcommit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.JiraTicket
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

class JiraTicketDialog(
    private val project: Project,
    private val ticket: JiraTicket
) : DialogWrapper(project, true) {

    init {
        title = "JIRA Ticket: ${ticket.key}"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(600, 400)

        val htmlContent = buildHtmlContent()

        val editorPane = JEditorPane().apply {
            contentType = "text/html"
            text = htmlContent
            isEditable = false
            border = JBUI.Borders.empty(10)
        }

        val scrollPane = JBScrollPane(editorPane).apply {
            border = JBUI.Borders.empty()
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun buildHtmlContent(): String {
        return buildString {
            append("<html><body style='font-family: sans-serif; font-size: 12px;'>")

            // Summary
            append("<h2 style='margin-bottom: 5px;'>${escapeHtml(ticket.summary)}</h2>")

            // Metadata
            append("<p style='color: gray;'>")
            append("<b>Type:</b> ${escapeHtml(ticket.issueType)} | ")
            append("<b>Status:</b> ${escapeHtml(ticket.status)}")
            append("</p>")

            // Description
            ticket.description?.let { desc ->
                append("<h3>Description</h3>")
                append("<p>${escapeHtml(desc).replace("\n", "<br>")}</p>")
            }

            // Acceptance Criteria
            ticket.acceptanceCriteria?.let { ac ->
                append("<h3>Acceptance Criteria</h3>")
                append("<p>${escapeHtml(ac).replace("\n", "<br>")}</p>")
            }

            append("</body></html>")
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    override fun createActions(): Array<javax.swing.Action> {
        return arrayOf(okAction)
    }
}

class JiraTicketNotFoundDialog(
    private val project: Project,
    private val ticketKey: String,
    private val errorMessage: String
) : DialogWrapper(project, true) {

    init {
        title = "JIRA Ticket Not Found"
        setOKButtonText("OK")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val label = JBLabel("<html><body style='width: 300px;'>" +
                "<p>Could not fetch JIRA ticket: <b>$ticketKey</b></p>" +
                "<p style='color: gray;'>$errorMessage</p>" +
                "</body></html>")
        label.border = JBUI.Borders.empty(10)

        panel.add(label, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<javax.swing.Action> {
        return arrayOf(okAction)
    }
}
