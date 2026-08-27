package com.jirasmartcommit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.flows.CommitFlow
import com.jirasmartcommit.flows.PRFlow
import com.jirasmartcommit.services.ChatMessage
import com.jirasmartcommit.services.ChatRole
import com.jirasmartcommit.services.VibeSessionController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

class VibeSessionPanel(
    private val project: Project,
    private val controller: VibeSessionController
) : JPanel(BorderLayout()) {

    private val headerLabel = JBLabel().apply {
        border = JBUI.Borders.empty(6, 10)
        font = font.deriveFont(font.size2D + 1f)
    }
    private val branchLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        font = font.deriveFont(font.size2D - 1f)
        border = JBUI.Borders.empty(0, 10, 6, 10)
    }

    private val commitButton = JButton("Commit…").apply {
        toolTipText = "Generate commit message and commit in this session's worktree"
    }
    private val createPrButton = JButton("Create PR…").apply {
        toolTipText = "Generate PR description and create a PR against the session's base branch"
    }
    private val revealButton = JButton("Reveal").apply {
        toolTipText = "Reveal worktree in Finder"
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
    private val sendButton = JButton("Send").apply { isEnabled = false }

    private var isBusy = false
    private var renderedMessageCount = 0
    private var initialized = false

    init {
        buildUi()
        wireListeners()
        refreshHeader()
    }

    private fun buildUi() {
        val headerStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerLabel)
            add(branchLabel)
        }
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(commitButton)
            add(Box.createHorizontalStrut(6))
            add(createPrButton)
            add(Box.createHorizontalStrut(6))
            add(revealButton)
            add(Box.createHorizontalGlue())
        }

        val topBar = JPanel(BorderLayout()).apply {
            add(headerStack, BorderLayout.CENTER)
            add(toolbar, BorderLayout.EAST)
            border = JBUI.Borders.customLine(
                UIManager.getColor("Separator.separatorColor"),
                0, 0, 1, 0
            )
        }

        val inputPanel = JPanel(BorderLayout()).apply {
            val inputScroll = JBScrollPane(inputArea).apply { border = JBUI.Borders.empty() }
            add(inputScroll, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
            border = JBUI.Borders.customLine(
                UIManager.getColor("Separator.separatorColor"),
                1, 0, 0, 0
            )
        }

        add(topBar, BorderLayout.NORTH)
        add(chatScrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)
    }

    private fun wireListeners() {
        sendButton.addActionListener { sendMessage() }

        commitButton.addActionListener {
            val rootDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(controller.session.worktreePath)
            if (rootDir == null) {
                addBubble(ChatRole.ERROR, "Could not locate worktree at ${controller.session.worktreePath}")
                return@addActionListener
            }
            CommitFlow.run(project, rootDir, controller.session.ticketKey)
        }

        createPrButton.addActionListener {
            val rootDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(controller.session.worktreePath)
            if (rootDir == null) {
                addBubble(ChatRole.ERROR, "Could not locate worktree at ${controller.session.worktreePath}")
                return@addActionListener
            }
            PRFlow.run(
                project = project,
                rootDir = rootDir,
                preferredBaseBranch = controller.session.baseBranch,
                ticketKeyHint = controller.session.ticketKey
            )
        }

        revealButton.addActionListener {
            try {
                val file = File(controller.session.worktreePath)
                if (file.exists() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(file)
                }
            } catch (_: Exception) {
            }
        }

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
        inputArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSendButton()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSendButton()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateSendButton()
        })
    }

    private fun refreshHeader() {
        val session = controller.session
        headerLabel.text = when {
            session.ticketSummary != null && session.ticketKey != null ->
                "${session.ticketKey}: ${session.ticketSummary}"
            session.ticketKey != null -> session.ticketKey
            else -> "Freeform session"
        }
        branchLabel.text = "Branch: ${session.branchName}  ·  Base: ${session.baseBranch}  ·  ${session.worktreePath}"
    }

    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        startInitialAITurn()
    }

    private fun startInitialAITurn() {
        setBusy(true)
        addBubble(ChatRole.SYSTEM, "Initializing session — scanning worktree and fetching JIRA ticket...")

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Initializing Code Vibing session...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = controller.initialize()
                ApplicationManager.getApplication().invokeLater {
                    renderMissingMessages()
                    addBubble(result.role, result.content)
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
        addBubble(ChatRole.USER, text)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "AI is thinking...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = controller.sendUserMessage(text)
                ApplicationManager.getApplication().invokeLater {
                    renderMissingMessages()
                    addBubble(result.role, result.content)
                    setBusy(false)
                }
            }
        })
    }

    private fun renderMissingMessages() {
        val history = controller.getConversationHistory()
        while (renderedMessageCount < history.size - 1) {
            val msg = history[renderedMessageCount]
            if (msg.role == ChatRole.SYSTEM) {
                addBubbleInternal(msg.role, msg.content)
            }
            renderedMessageCount++
        }
    }

    private fun addBubble(role: ChatRole, content: String) {
        addBubbleInternal(role, content)
        renderedMessageCount = controller.getConversationHistory().size
    }

    private fun addBubbleInternal(role: ChatRole, content: String) {
        val bubble = createBubble(role, content)
        chatArea.add(bubble)
        chatArea.add(Box.createVerticalStrut(8))
        chatArea.revalidate()
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
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun updateSendButton() {
        sendButton.isEnabled = !isBusy && inputArea.text.isNotBlank()
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        sendButton.isEnabled = !busy && inputArea.text.isNotBlank()
        inputArea.isEnabled = !busy
        sendButton.text = if (busy) "Thinking..." else "Send"
    }
}
