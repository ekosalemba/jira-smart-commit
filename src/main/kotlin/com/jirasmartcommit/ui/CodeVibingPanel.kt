package com.jirasmartcommit.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.jirasmartcommit.services.CodeVibingService
import com.jirasmartcommit.services.CreateSessionResult
import com.jirasmartcommit.services.CreateSessionSpec
import com.jirasmartcommit.services.SessionListener
import com.jirasmartcommit.services.VibeSessionController
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane

class CodeVibingPanel(private val project: Project) : JPanel(BorderLayout()), SessionListener {

    private val service = CodeVibingService.getInstance(project)

    private val tabbedPane = JBTabbedPane().apply {
        tabPlacement = JTabbedPane.TOP
    }

    private val emptyState = buildEmptyState()
    private val panelByController = mutableMapOf<String, VibeSessionPanel>()

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)
        service.addListener(this)
        refreshTabs()
    }

    private fun buildToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        toolbar.add(JButton("+ New Session").apply {
            addActionListener { openNewSessionDialog() }
        })
        toolbar.add(JButton("Close Current").apply {
            addActionListener { closeCurrentTab() }
        })
        toolbar.border = JBUI.Borders.customLine(
            javax.swing.UIManager.getColor("Separator.separatorColor"),
            0, 0, 1, 0
        )
        return toolbar
    }

    private fun buildEmptyState(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(40)
        }
        val title = JBLabel("No Code Vibing sessions yet.").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            font = font.deriveFont(font.size2D + 2f)
        }
        val hint = JBLabel("Create a session: pick a JIRA ticket and base branch.").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }
        val button = JButton("Start New Session").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener { openNewSessionDialog() }
        }
        panel.add(Box.createVerticalGlue())
        panel.add(title)
        panel.add(Box.createVerticalStrut(8))
        panel.add(hint)
        panel.add(Box.createVerticalStrut(16))
        panel.add(button)
        panel.add(Box.createVerticalGlue())
        return panel
    }

    fun openNewSessionDialog() {
        val dialog = NewVibeSessionDialog(project)
        if (!dialog.showAndGet()) return

        val spec = CreateSessionSpec(
            ticketKey = dialog.ticketKey(),
            ticketSummary = dialog.ticketSummary(),
            branchName = dialog.branchName(),
            baseBranch = dialog.baseBranch(),
            worktreePath = dialog.worktreePath(),
            createBranch = dialog.createBranchFlag(),
            fetchBaseFirst = dialog.fetchBaseFirst()
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Creating worktree at ${spec.worktreePath}...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = service.createSession(spec)
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is CreateSessionResult.Success -> {
                            selectControllerTab(result.controller)
                        }
                        is CreateSessionResult.Error -> {
                            Messages.showErrorDialog(project, result.message, "Code Vibing")
                        }
                    }
                }
            }
        })
    }

    private fun closeCurrentTab() {
        val index = tabbedPane.selectedIndex
        if (index < 0) return
        val component = tabbedPane.getComponentAt(index)
        val controller = panelByController.entries.firstOrNull { it.value === component }?.key?.let { service.getController(it) }
            ?: return
        promptCloseSession(controller)
    }

    private fun promptCloseSession(controller: VibeSessionController) {
        val choice = Messages.showYesNoCancelDialog(
            project,
            "Close session for ${controller.session.displayTitle}?\nWorktree: ${controller.session.worktreePath}",
            "Close Session",
            "Close & Remove Worktree",
            "Close (Keep Worktree)",
            "Cancel",
            null
        )
        when (choice) {
            Messages.YES -> service.closeSession(controller.session.id, removeWorktree = true)
            Messages.NO -> service.closeSession(controller.session.id, removeWorktree = false)
            else -> {}
        }
    }

    override fun onSessionsChanged() {
        ApplicationManager.getApplication().invokeLater { refreshTabs() }
    }

    private fun selectControllerTab(controller: VibeSessionController) {
        refreshTabs()
        val panel = panelByController[controller.session.id] ?: return
        val index = tabbedPane.indexOfComponent(panel)
        if (index >= 0) {
            tabbedPane.selectedIndex = index
        }
    }

    private fun refreshTabs() {
        val controllers = service.listSessions()
        if (controllers.isEmpty()) {
            tabbedPane.isVisible = false
            if (getComponentZOrder(emptyState) == -1) {
                add(emptyState, BorderLayout.CENTER)
            }
            revalidate()
            repaint()
            return
        } else {
            if (getComponentZOrder(emptyState) != -1) {
                remove(emptyState)
            }
            tabbedPane.isVisible = true
            if (getComponentZOrder(tabbedPane) == -1) {
                add(tabbedPane, BorderLayout.CENTER)
            }
        }

        // Add new tabs for new controllers
        val existingIds = panelByController.keys.toSet()
        val currentIds = controllers.map { it.session.id }.toSet()

        // Remove tabs whose sessions are gone
        for (id in existingIds - currentIds) {
            val panel = panelByController.remove(id) ?: continue
            val idx = tabbedPane.indexOfComponent(panel)
            if (idx >= 0) tabbedPane.removeTabAt(idx)
        }

        // Add tabs for new sessions
        for (controller in controllers) {
            if (panelByController.containsKey(controller.session.id)) continue
            val panel = VibeSessionPanel(project, controller)
            panelByController[controller.session.id] = panel
            val title = controller.session.displayTitle
            tabbedPane.addTab(title, panel)
            val idx = tabbedPane.indexOfComponent(panel)
            if (idx >= 0) {
                tabbedPane.setTabComponentAt(idx, buildTabHeader(controller))
            }
            // Kick off the initial AI turn lazily once Swing has displayed the panel
            ApplicationManager.getApplication().invokeLater {
                panel.ensureInitialized()
            }
        }

        revalidate()
        repaint()
    }

    private fun buildTabHeader(controller: VibeSessionController): JComponent {
        val container = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        container.isOpaque = false

        val label = JLabel(controller.session.displayTitle).apply {
            toolTipText = "Branch: ${controller.session.branchName}  ·  ${controller.session.worktreePath}"
        }
        val closeButton = JLabel("✕").apply {
            border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "Close session"
            preferredSize = Dimension(14, 14)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    promptCloseSession(controller)
                }
            })
        }
        container.add(label)
        container.add(closeButton)
        return container
    }

    fun dispose() {
        service.removeListener(this)
    }
}
