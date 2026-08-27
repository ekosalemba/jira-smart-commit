package com.jirasmartcommit.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.jirasmartcommit.flows.PRFlow
import com.jirasmartcommit.services.GitService

class GeneratePRDescriptionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rootDir = GitService.getInstance(project).getCurrentRepository()?.root ?: return
        PRFlow.run(project, rootDir)
    }
}
