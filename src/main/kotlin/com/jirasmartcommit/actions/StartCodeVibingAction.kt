package com.jirasmartcommit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.jirasmartcommit.ui.CodeVibingPanel

class StartCodeVibingAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Vibing") ?: return

        toolWindow.show {
            // Auto-start a new session when the tool window opens
            val content = toolWindow.contentManager.getContent(0)
            val panel = content?.component as? CodeVibingPanel
            panel?.startNewSession()
        }
    }
}
