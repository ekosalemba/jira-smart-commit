package com.jirasmartcommit.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jirasmartcommit.settings.PluginSettings
import java.net.HttpURLConnection
import java.net.URL

@Service(Service.Level.PROJECT)
class PullRequestService(private val project: Project) {

    private val logger = Logger.getInstance(PullRequestService::class.java)
    private val gson = Gson()

    data class PRCreateResult(
        val success: Boolean,
        val prUrl: String? = null,
        val error: String? = null
    )

    fun createPullRequest(
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String
    ): PRCreateResult {
        val gitService = GitService.getInstance(project)
        val remoteUrl = gitService.getRemoteUrl()
            ?: return PRCreateResult(false, error = "No remote URL found")

        val repoInfo = parseRemoteUrl(remoteUrl)
            ?: return PRCreateResult(false, error = "Could not parse remote URL: $remoteUrl")

        return when (repoInfo.platform) {
            GitPlatform.BITBUCKET -> createBitbucketPR(repoInfo, title, description, sourceBranch, targetBranch)
            GitPlatform.GITHUB -> createGitHubPR(repoInfo, title, description, sourceBranch, targetBranch)
            GitPlatform.GITLAB -> createGitLabPR(repoInfo, title, description, sourceBranch, targetBranch)
            GitPlatform.UNKNOWN -> PRCreateResult(false, error = "Unsupported git platform")
        }
    }

    private fun createBitbucketPR(
        repoInfo: RepoInfo,
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String
    ): PRCreateResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return PRCreateResult(false, error = "Git platform token not configured")
        }

        val apiUrl = "https://api.bitbucket.org/2.0/repositories/${repoInfo.owner}/${repoInfo.repo}/pullrequests"

        val requestBody = JsonObject().apply {
            addProperty("title", title)
            addProperty("description", description)
            add("source", JsonObject().apply {
                add("branch", JsonObject().apply {
                    addProperty("name", sourceBranch)
                })
            })
            add("destination", JsonObject().apply {
                add("branch", JsonObject().apply {
                    addProperty("name", targetBranch)
                })
            })
        }

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(gson.toJson(requestBody).toByteArray())
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val prUrl = jsonResponse
                    .getAsJsonObject("links")
                    ?.getAsJsonObject("html")
                    ?.get("href")
                    ?.asString

                PRCreateResult(true, prUrl = prUrl)
            } else {
                val errorResponse = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) {
                    "Unknown error"
                }
                logger.warn("Bitbucket PR creation failed: $responseCode - $errorResponse")

                val errorMessage = when (responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> "Invalid or expired access token"
                    HttpURLConnection.HTTP_FORBIDDEN -> "Access denied. Check token permissions"
                    HttpURLConnection.HTTP_NOT_FOUND -> "Repository not found or no access"
                    HttpURLConnection.HTTP_BAD_REQUEST -> parseErrorMessage(errorResponse) ?: "Invalid request"
                    HttpURLConnection.HTTP_CONFLICT -> "A pull request for this branch already exists"
                    else -> "API error: $responseCode"
                }

                PRCreateResult(false, error = errorMessage)
            }
        } catch (e: Exception) {
            logger.error("Failed to create Bitbucket PR", e)
            PRCreateResult(false, error = "Network error: ${e.message}")
        }
    }

    private fun createGitHubPR(
        repoInfo: RepoInfo,
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String
    ): PRCreateResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return PRCreateResult(false, error = "Git platform token not configured")
        }

        val apiUrl = "https://api.github.com/repos/${repoInfo.owner}/${repoInfo.repo}/pulls"

        val requestBody = JsonObject().apply {
            addProperty("title", title)
            addProperty("body", description)
            addProperty("head", sourceBranch)
            addProperty("base", targetBranch)
        }

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(gson.toJson(requestBody).toByteArray())
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val prUrl = jsonResponse.get("html_url")?.asString

                PRCreateResult(true, prUrl = prUrl)
            } else {
                val errorResponse = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) {
                    "Unknown error"
                }
                logger.warn("GitHub PR creation failed: $responseCode - $errorResponse")

                val errorMessage = when (responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> "Invalid or expired access token"
                    HttpURLConnection.HTTP_FORBIDDEN -> "Access denied. Check token permissions"
                    HttpURLConnection.HTTP_NOT_FOUND -> "Repository not found or no access"
                    422 -> parseErrorMessage(errorResponse) ?: "Validation failed (PR may already exist)"
                    else -> "API error: $responseCode"
                }

                PRCreateResult(false, error = errorMessage)
            }
        } catch (e: Exception) {
            logger.error("Failed to create GitHub PR", e)
            PRCreateResult(false, error = "Network error: ${e.message}")
        }
    }

    private fun createGitLabPR(
        repoInfo: RepoInfo,
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String
    ): PRCreateResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return PRCreateResult(false, error = "Git platform token not configured")
        }

        val projectPath = java.net.URLEncoder.encode("${repoInfo.owner}/${repoInfo.repo}", "UTF-8")
        val apiUrl = "${repoInfo.baseUrl}/api/v4/projects/$projectPath/merge_requests"

        val requestBody = JsonObject().apply {
            addProperty("title", title)
            addProperty("description", description)
            addProperty("source_branch", sourceBranch)
            addProperty("target_branch", targetBranch)
        }

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("PRIVATE-TOKEN", token)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            connection.outputStream.use { os ->
                os.write(gson.toJson(requestBody).toByteArray())
            }

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val prUrl = jsonResponse.get("web_url")?.asString

                PRCreateResult(true, prUrl = prUrl)
            } else {
                val errorResponse = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) {
                    "Unknown error"
                }
                logger.warn("GitLab MR creation failed: $responseCode - $errorResponse")

                val errorMessage = when (responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> "Invalid or expired access token"
                    HttpURLConnection.HTTP_FORBIDDEN -> "Access denied. Check token permissions"
                    HttpURLConnection.HTTP_NOT_FOUND -> "Project not found or no access"
                    HttpURLConnection.HTTP_CONFLICT -> "A merge request for this branch already exists"
                    else -> "API error: $responseCode"
                }

                PRCreateResult(false, error = errorMessage)
            }
        } catch (e: Exception) {
            logger.error("Failed to create GitLab MR", e)
            PRCreateResult(false, error = "Network error: ${e.message}")
        }
    }

    private fun parseErrorMessage(errorResponse: String): String? {
        return try {
            val json = gson.fromJson(errorResponse, JsonObject::class.java)
            json.get("message")?.asString
                ?: json.get("error")?.asString
                ?: json.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("message")?.asString
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRemoteUrl(remoteUrl: String): RepoInfo? {
        // Handle SSH URLs: git@github.com:owner/repo.git
        val sshPattern = Regex("""git@([^:]+):([^/]+)/(.+?)(?:\.git)?$""")
        val sshMatch = sshPattern.find(remoteUrl)
        if (sshMatch != null) {
            val (host, owner, repo) = sshMatch.destructured
            val platform = detectPlatform(host)
            val baseUrl = "https://$host"
            return RepoInfo(platform, baseUrl, owner, repo.removeSuffix(".git"))
        }

        // Handle HTTPS URLs: https://github.com/owner/repo.git
        val httpsPattern = Regex("""https?://([^/]+)/([^/]+)/(.+?)(?:\.git)?$""")
        val httpsMatch = httpsPattern.find(remoteUrl)
        if (httpsMatch != null) {
            val (host, owner, repo) = httpsMatch.destructured
            val platform = detectPlatform(host)
            val baseUrl = "https://$host"
            return RepoInfo(platform, baseUrl, owner, repo.removeSuffix(".git"))
        }

        return null
    }

    private fun detectPlatform(host: String): GitPlatform {
        return when {
            host.contains("github") -> GitPlatform.GITHUB
            host.contains("gitlab") -> GitPlatform.GITLAB
            host.contains("bitbucket") -> GitPlatform.BITBUCKET
            else -> GitPlatform.UNKNOWN
        }
    }

    companion object {
        fun getInstance(project: Project): PullRequestService {
            return project.getService(PullRequestService::class.java)
        }
    }
}
