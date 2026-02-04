package com.jirasmartcommit.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirasmartcommit.settings.PluginSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

@Serializable
data class JiraTicket(
    val key: String,
    val fields: JiraFields
) {
    val summary: String get() = fields.summary
    val description: String? get() = fields.description?.content
        ?.flatMap { it.content ?: emptyList() }
        ?.mapNotNull { it.text }
        ?.joinToString("\n")
    val issueType: String get() = fields.issuetype?.name ?: "Task"
    val status: String get() = fields.status?.name ?: "Unknown"
    val acceptanceCriteria: String? get() = fields.customfield_10016 ?: fields.customfield_10020
}

@Serializable
data class JiraFields(
    val summary: String,
    val description: AdfDocument? = null,
    val issuetype: IssueType? = null,
    val status: Status? = null,
    // Common acceptance criteria custom fields
    @SerialName("customfield_10016")
    val customfield_10016: String? = null,
    @SerialName("customfield_10020")
    val customfield_10020: String? = null
)

@Serializable
data class AdfDocument(
    val type: String? = null,
    val version: Int? = null,
    val content: List<AdfNode>? = null
)

@Serializable
data class AdfNode(
    val type: String? = null,
    val content: List<AdfTextNode>? = null,
    val text: String? = null
)

@Serializable
data class AdfTextNode(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class IssueType(
    val name: String
)

@Serializable
data class Status(
    val name: String
)

@Serializable
data class JiraError(
    val errorMessages: List<String>? = null,
    val errors: Map<String, String>? = null
)

sealed class JiraResult<out T> {
    data class Success<T>(val data: T) : JiraResult<T>()
    data class Error(val message: String) : JiraResult<Nothing>()
}

@Service(Service.Level.PROJECT)
class JiraService(private val project: Project) {

    private val logger = Logger.getInstance(JiraService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val settings: PluginSettings
        get() = PluginSettings.instance

    suspend fun fetchTicket(ticketKey: String): JiraResult<JiraTicket> = withContext(Dispatchers.IO) {
        if (!settings.isJiraConfigured()) {
            return@withContext JiraResult.Error("JIRA is not configured. Please configure in Settings → Tools → JIRA Smart Commit")
        }

        try {
            val url = "${settings.jiraUrl}/rest/api/3/issue/$ticketKey"
            val credentials = Base64.getEncoder().encodeToString(
                "${settings.jiraEmail}:${settings.jiraApiToken}".toByteArray()
            )

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Basic $credentials")
                .header("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> {
                    val body = response.body?.string()
                    if (body != null) {
                        val ticket = json.decodeFromString<JiraTicket>(body)
                        logger.info("Successfully fetched JIRA ticket: $ticketKey")
                        JiraResult.Success(ticket)
                    } else {
                        JiraResult.Error("Empty response from JIRA")
                    }
                }
                401 -> JiraResult.Error("JIRA authentication failed. Please check your credentials.")
                403 -> JiraResult.Error("Access denied to JIRA ticket $ticketKey")
                404 -> JiraResult.Error("JIRA ticket $ticketKey not found")
                else -> {
                    val errorBody = response.body?.string()
                    val errorMessage = try {
                        val error = json.decodeFromString<JiraError>(errorBody ?: "")
                        error.errorMessages?.firstOrNull() ?: error.errors?.values?.firstOrNull()
                        ?: "Unknown error"
                    } catch (e: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    JiraResult.Error(errorMessage)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch JIRA ticket: $ticketKey", e)
            JiraResult.Error("Failed to connect to JIRA: ${e.message}")
        }
    }

    fun extractTicketKeyFromBranch(branchName: String): String? {
        // Common patterns:
        // feature/BOT-1234-description
        // BOT-1234-description
        // BOT-1234_description
        // feature/BOT-1234
        val patterns = listOf(
            Regex("""([A-Z]+-\d+)"""),  // Standard JIRA key pattern
            Regex("""([a-zA-Z]+-\d+)""", RegexOption.IGNORE_CASE)  // Case insensitive
        )

        for (pattern in patterns) {
            val match = pattern.find(branchName)
            if (match != null) {
                return match.groupValues[1].uppercase()
            }
        }

        return null
    }

    fun buildTicketContext(ticket: JiraTicket): String {
        return buildString {
            appendLine("## JIRA Ticket: ${ticket.key}")
            appendLine()
            appendLine("**Summary:** ${ticket.summary}")
            appendLine()
            appendLine("**Type:** ${ticket.issueType}")
            appendLine()
            appendLine("**Status:** ${ticket.status}")

            ticket.description?.let { desc ->
                appendLine()
                appendLine("**Description:**")
                appendLine(desc)
            }

            ticket.acceptanceCriteria?.let { ac ->
                appendLine()
                appendLine("**Acceptance Criteria:**")
                appendLine(ac)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): JiraService {
            return project.getService(JiraService::class.java)
        }
    }
}
