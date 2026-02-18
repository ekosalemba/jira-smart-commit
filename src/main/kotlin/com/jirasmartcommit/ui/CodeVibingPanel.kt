package com.jirasmartcommit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.ChatMessage
import com.jirasmartcommit.services.ChatRole
import com.jirasmartcommit.services.CodeVibingService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*

class CodeVibingPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = CodeVibingService.getInstance(project)

    private val ticketLabel = JBLabel("No session active").apply {
        border = JBUI.Borders.empty(8, 12)
        font = font.deriveFont(font.size2D + 1f)
    }

    private val chatArea = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    private val chatScrollPane = JBScrollPane(chatArea).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }

    private val inputArea = JBTextArea().apply {
        rows = 3
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create("Monospaced", 13)
        border = JBUI.Borders.empty(4)
        emptyText.text = "Type a message... (Ctrl+Enter to send)"
    }

    private val sendButton = JButton("Send").apply {
        isEnabled = false
    }

    private val newSessionButton = JButton("New Session").apply {
        toolTipText = "Start a new Code Vibing session"
    }

    private var isBusy = false

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        // Top bar with ticket info
        val topBar = JPanel(BorderLayout()).apply {
            add(ticketLabel, BorderLayout.CENTER)
            add(newSessionButton, BorderLayout.EAST)
            border = JBUI.Borders.customLine(UIManager.getColor("Separator.separatorColor"), 0, 0, 1, 0)
        }

        // Bottom input area
        val inputPanel = JPanel(BorderLayout()).apply {
            val inputScroll = JBScrollPane(inputArea).apply {
                border = JBUI.Borders.empty()
            }
            add(inputScroll, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
            border = JBUI.Borders.customLine(UIManager.getColor("Separator.separatorColor"), 1, 0, 0, 0)
        }

        add(topBar, BorderLayout.NORTH)
        add(chatScrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        sendButton.addActionListener { sendMessage() }

        newSessionButton.addActionListener { startNewSession() }

        inputArea.addKeyListener(object : KeyListener {
            override fun keyTyped(e: KeyEvent) {}
            override fun keyReleased(e: KeyEvent) {}
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    e.consume()
                    sendMessage()
                }
                updateSendButton()
            }
        })

        // Also update send button when text changes
        inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSendButton()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSendButton()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateSendButton()
        })
    }

    private fun updateSendButton() {
        sendButton.isEnabled = !isBusy && inputArea.text.isNotBlank()
    }

    fun startNewSession() {
        chatArea.removeAll()
        chatArea.revalidate()
        chatArea.repaint()
        inputArea.text = ""
        setBusy(true)

        addMessageBubble(ChatRole.SYSTEM, "Starting new session... Scanning project and fetching JIRA ticket.")

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Initializing Code Vibing session...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = service.initializeSession()

                ApplicationManager.getApplication().invokeLater {
                    // Update ticket label
                    val summary = service.getTicketSummary()
                    ticketLabel.text = summary ?: "Freeform mode (no JIRA ticket)"

                    addMessageBubble(result.role, result.content)
                    setBusy(false)
                }
            }
        })
    }

    private fun sendMessage() {
        val text = inputArea.text?.trim() ?: return
        if (text.isBlank() || isBusy) return

        inputArea.text = ""
        setBusy(true)

        addMessageBubble(ChatRole.USER, text)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "AI is thinking...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = service.sendUserMessage(text)

                ApplicationManager.getApplication().invokeLater {
                    // Render any system messages (file reads/writes) that were added
                    renderMissingMessages()
                    addMessageBubble(result.role, result.content)
                    setBusy(false)
                }
            }
        })
    }

    private var renderedMessageCount = 0

    private fun renderMissingMessages() {
        val history = service.getConversationHistory()
        // Render any messages we haven't shown yet, excluding the last one (which we'll render separately)
        while (renderedMessageCount < history.size - 1) {
            val msg = history[renderedMessageCount]
            if (msg.role == ChatRole.SYSTEM) {
                addMessageBubbleInternal(msg.role, msg.content)
            }
            renderedMessageCount++
        }
    }

    private fun addMessageBubble(role: ChatRole, content: String) {
        addMessageBubbleInternal(role, content)
        renderedMessageCount = service.getConversationHistory().size
    }

    private fun addMessageBubbleInternal(role: ChatRole, content: String) {
        val bubble = createBubble(role, content)
        chatArea.add(bubble)
        chatArea.add(Box.createVerticalStrut(8))
        chatArea.revalidate()

        // Auto-scroll to bottom
        SwingUtilities.invokeLater {
            val scrollBar = chatScrollPane.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        }
    }

    private fun createBubble(role: ChatRole, content: String): JPanel {
        val roleLabel = when (role) {
            ChatRole.USER -> "You"
            ChatRole.ASSISTANT -> "AI"
            ChatRole.SYSTEM -> "System"
            ChatRole.ERROR -> "Error"
        }

        val bgColor = when (role) {
            ChatRole.USER -> UIManager.getColor("EditorPane.background") ?: Color(0x2B, 0x2D, 0x30)
            ChatRole.ASSISTANT -> UIManager.getColor("Panel.background") ?: Color(0x3C, 0x3F, 0x41)
            ChatRole.SYSTEM -> UIManager.getColor("Panel.background") ?: Color(0x3C, 0x3F, 0x41)
            ChatRole.ERROR -> Color(0x5C, 0x20, 0x20)
        }

        val fgColor = when (role) {
            ChatRole.ERROR -> Color(0xFF, 0x80, 0x80)
            else -> UIManager.getColor("Label.foreground") ?: Color.WHITE
        }

        val headerColor = when (role) {
            ChatRole.USER -> Color(0x58, 0x9D, 0xF6)
            ChatRole.ASSISTANT -> Color(0x6A, 0x9F, 0x55)
            ChatRole.SYSTEM -> Color(0xBB, 0xBB, 0xBB)
            ChatRole.ERROR -> Color(0xFF, 0x60, 0x60)
        }

        return JPanel(BorderLayout()).apply {
            background = bgColor
            border = JBUI.Borders.empty(8, 12)
            isOpaque = true

            val header = JBLabel(roleLabel).apply {
                foreground = headerColor
                font = font.deriveFont(java.awt.Font.BOLD, font.size2D - 1f)
                border = JBUI.Borders.emptyBottom(4)
            }

            val textPane = JTextArea(content).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = bgColor
                foreground = fgColor
                font = JBUI.Fonts.create("Monospaced", 12)
                border = JBUI.Borders.empty()
                caretPosition = 0
            }

            add(header, BorderLayout.NORTH)
            add(textPane, BorderLayout.CENTER)

            // Limit max width
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        sendButton.isEnabled = !busy && inputArea.text.isNotBlank()
        inputArea.isEnabled = !busy
        sendButton.text = if (busy) "Thinking..." else "Send"
    }
}
