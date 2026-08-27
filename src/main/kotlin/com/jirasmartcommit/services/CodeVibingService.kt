package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

enum class ChatRole { USER, ASSISTANT, SYSTEM, ERROR }

data class ChatMessage(
    val role: ChatRole,
    val content: String
)

data class FileChangeResult(
    val path: String,
    val written: Boolean,
    val error: String? = null
)

data class CreateSessionSpec(
    val ticketKey: String?,
    val ticketSummary: String?,
    val branchName: String,
    val baseBranch: String,
    val worktreePath: String,
    val createBranch: Boolean,
    val fetchBaseFirst: Boolean
)

sealed class CreateSessionResult {
    data class Success(val controller: VibeSessionController) : CreateSessionResult()
    data class Error(val message: String) : CreateSessionResult()
}

interface SessionListener {
    fun onSessionsChanged()
}

@Service(Service.Level.PROJECT)
class CodeVibingService(private val project: Project) {

    private val logger = Logger.getInstance(CodeVibingService::class.java)

    private val controllers = mutableListOf<VibeSessionController>()
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    fun addListener(listener: SessionListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    fun listSessions(): List<VibeSessionController> = controllers.toList()

    fun getController(id: String): VibeSessionController? =
        controllers.find { it.session.id == id }

    fun createSession(spec: CreateSessionSpec): CreateSessionResult {
        val gitService = GitService.getInstance(project)

        if (spec.fetchBaseFirst) {
            val fetchResult = gitService.fetchRemote("origin", spec.baseBranch)
            if (fetchResult is GitResult.Error) {
                logger.warn("Fetch failed (continuing anyway): ${fetchResult.message}")
            }
        }

        val addResult = gitService.addWorktree(
            worktreePath = spec.worktreePath,
            branchName = spec.branchName,
            baseBranch = spec.baseBranch,
            createBranch = spec.createBranch
        )
        if (addResult is GitResult.Error) {
            return CreateSessionResult.Error(addResult.message)
        }

        val session = VibeSession(
            ticketKey = spec.ticketKey,
            ticketSummary = spec.ticketSummary,
            branchName = spec.branchName,
            baseBranch = spec.baseBranch,
            worktreePath = spec.worktreePath
        )
        val controller = VibeSessionController(project, session)
        controllers.add(controller)
        notifyListeners()
        return CreateSessionResult.Success(controller)
    }

    fun closeSession(id: String, removeWorktree: Boolean): GitResult<Unit> {
        val controller = getController(id) ?: return GitResult.Error("Session not found")
        controllers.remove(controller)

        var result: GitResult<Unit> = GitResult.Success(Unit)
        if (removeWorktree) {
            val gitService = GitService.getInstance(project)
            result = gitService.removeWorktree(controller.session.worktreePath, force = true)
        }

        notifyListeners()
        return result
    }

    private fun notifyListeners() {
        listeners.forEach {
            try {
                it.onSessionsChanged()
            } catch (e: Exception) {
                logger.warn("Listener failed", e)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): CodeVibingService {
            return project.getService(CodeVibingService::class.java)
        }
    }
}
