package com.jirasmartcommit.services

import java.util.UUID

data class VibeSession(
    val id: String = UUID.randomUUID().toString(),
    val ticketKey: String?,
    val ticketSummary: String?,
    val branchName: String,
    val baseBranch: String,
    val worktreePath: String,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val displayTitle: String
        get() = ticketKey ?: branchName
}
