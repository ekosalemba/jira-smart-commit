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

    data class Reviewer(
        val id: String,           // UUID for Bitbucket, username for GitHub, ID for GitLab
        val username: String,
        val displayName: String,
        val avatarUrl: String? = null
    )

    data class ReviewersResult(
        val success: Boolean,
        val reviewers: List<Reviewer> = emptyList(),
        val error: String? = null
    )

    fun getRepoInfo(): RepoInfo? {
        val gitService = GitService.getInstance(project)
        val remoteUrl = gitService.getRemoteUrl() ?: return null
        return parseRemoteUrl(remoteUrl)
    }

    fun getRepositoryMembers(): ReviewersResult {
        val repoInfo = getRepoInfo()
            ?: return ReviewersResult(false, error = "No remote URL found")

        return when (repoInfo.platform) {
            GitPlatform.BITBUCKET -> getBitbucketMembers(repoInfo)
            GitPlatform.GITHUB -> getGitHubMembers(repoInfo)
            GitPlatform.GITLAB -> getGitLabMembers(repoInfo)
            GitPlatform.UNKNOWN -> ReviewersResult(false, error = "Unsupported platform")
        }
    }

    fun getDefaultReviewers(targetBranch: String): ReviewersResult {
        val repoInfo = getRepoInfo()
            ?: return ReviewersResult(false, error = "No remote URL found")

        return when (repoInfo.platform) {
            GitPlatform.BITBUCKET -> getBitbucketDefaultReviewers(repoInfo, targetBranch)
            else -> ReviewersResult(true, reviewers = emptyList()) // Other platforms don't have default reviewers config
        }
    }

    private fun getBitbucketMembers(repoInfo: RepoInfo): ReviewersResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return ReviewersResult(false, error = "Git platform token not configured")
        }

        val allReviewers = mutableMapOf<String, Reviewer>()

        // 1. Get default reviewers (no admin required)
        val defaultReviewers = getBitbucketDefaultReviewersInternal(repoInfo)
        defaultReviewers.forEach { allReviewers[it.id] = it }

        // 2. Get all users with repo access (requires admin token)
        try {
            val permissionsUrl = "https://api.bitbucket.org/2.0/repositories/${repoInfo.owner}/${repoInfo.repo}/permissions-config/users?pagelen=100"
            val connection = URL(permissionsUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val values = jsonResponse.getAsJsonArray("values")

                values?.forEach { element ->
                    val permission = element.asJsonObject
                    val user = permission.getAsJsonObject("user")
                    if (user != null) {
                        val uuid = user.get("uuid")?.asString
                        if (uuid != null && !allReviewers.containsKey(uuid)) {
                            val username = user.get("username")?.asString ?: user.get("nickname")?.asString ?: ""
                            val displayName = user.get("display_name")?.asString ?: username
                            val avatarUrl = user.getAsJsonObject("links")?.getAsJsonObject("avatar")?.get("href")?.asString
                            allReviewers[uuid] = Reviewer(uuid, username, displayName, avatarUrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch repository permissions (may require admin token)", e)
        }

        return if (allReviewers.isNotEmpty()) {
            ReviewersResult(true, allReviewers.values.toList().sortedBy { it.displayName })
        } else {
            ReviewersResult(true, emptyList())
        }
    }

    private fun getBitbucketDefaultReviewersInternal(repoInfo: RepoInfo): List<Reviewer> {
        val token = PluginSettings.instance.gitPlatformToken
        val apiUrl = "https://api.bitbucket.org/2.0/repositories/${repoInfo.owner}/${repoInfo.repo}/default-reviewers"

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val values = jsonResponse.getAsJsonArray("values") ?: return emptyList()

                values.mapNotNull { element ->
                    val user = element.asJsonObject
                    val uuid = user.get("uuid")?.asString ?: return@mapNotNull null
                    val username = user.get("username")?.asString ?: user.get("nickname")?.asString ?: ""
                    val displayName = user.get("display_name")?.asString ?: username
                    val avatarUrl = user.getAsJsonObject("links")?.getAsJsonObject("avatar")?.get("href")?.asString

                    Reviewer(uuid, username, displayName, avatarUrl)
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch Bitbucket default reviewers", e)
            emptyList()
        }
    }

    private fun getBitbucketDefaultReviewers(repoInfo: RepoInfo, targetBranch: String): ReviewersResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return ReviewersResult(false, error = "Git platform token not configured")
        }

        val apiUrl = "https://api.bitbucket.org/2.0/repositories/${repoInfo.owner}/${repoInfo.repo}/default-reviewers"

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val values = jsonResponse.getAsJsonArray("values") ?: return ReviewersResult(true, emptyList())

                val reviewers = values.mapNotNull { element ->
                    val user = element.asJsonObject
                    val uuid = user.get("uuid")?.asString ?: return@mapNotNull null
                    val username = user.get("username")?.asString ?: user.get("nickname")?.asString ?: ""
                    val displayName = user.get("display_name")?.asString ?: username
                    val avatarUrl = user.getAsJsonObject("links")?.getAsJsonObject("avatar")?.get("href")?.asString

                    Reviewer(uuid, username, displayName, avatarUrl)
                }

                ReviewersResult(true, reviewers)
            } else {
                logger.warn("Failed to fetch Bitbucket default reviewers: $responseCode")
                ReviewersResult(true, emptyList()) // Return empty list, not an error
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch Bitbucket default reviewers", e)
            ReviewersResult(true, emptyList())
        }
    }

    private fun getGitHubMembers(repoInfo: RepoInfo): ReviewersResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return ReviewersResult(false, error = "Git platform token not configured")
        }

        val apiUrl = "https://api.github.com/repos/${repoInfo.owner}/${repoInfo.repo}/collaborators?per_page=100"

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonArray = gson.fromJson(response, com.google.gson.JsonArray::class.java)

                val reviewers = jsonArray.mapNotNull { element ->
                    val user = element.asJsonObject
                    val username = user.get("login")?.asString ?: return@mapNotNull null
                    val avatarUrl = user.get("avatar_url")?.asString

                    Reviewer(username, username, username, avatarUrl)
                }

                ReviewersResult(true, reviewers)
            } else {
                logger.warn("Failed to fetch GitHub collaborators: $responseCode")
                ReviewersResult(false, error = "Failed to fetch collaborators: $responseCode")
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch GitHub collaborators", e)
            ReviewersResult(false, error = "Network error: ${e.message}")
        }
    }

    private fun getGitLabMembers(repoInfo: RepoInfo): ReviewersResult {
        val token = PluginSettings.instance.gitPlatformToken
        if (token.isBlank()) {
            return ReviewersResult(false, error = "Git platform token not configured")
        }

        val projectPath = java.net.URLEncoder.encode("${repoInfo.owner}/${repoInfo.repo}", "UTF-8")
        val apiUrl = "${repoInfo.baseUrl}/api/v4/projects/$projectPath/members/all?per_page=100"

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("PRIVATE-TOKEN", token)

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonArray = gson.fromJson(response, com.google.gson.JsonArray::class.java)

                val reviewers = jsonArray.mapNotNull { element ->
                    val user = element.asJsonObject
                    val id = user.get("id")?.asString ?: return@mapNotNull null
                    val username = user.get("username")?.asString ?: return@mapNotNull null
                    val displayName = user.get("name")?.asString ?: username
                    val avatarUrl = user.get("avatar_url")?.asString

                    Reviewer(id, username, displayName, avatarUrl)
                }

                ReviewersResult(true, reviewers)
            } else {
                logger.warn("Failed to fetch GitLab members: $responseCode")
                ReviewersResult(false, error = "Failed to fetch members: $responseCode")
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch GitLab members", e)
            ReviewersResult(false, error = "Network error: ${e.message}")
        }
    }

    fun createPullRequest(
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String,
        reviewers: List<Reviewer> = emptyList()
    ): PRCreateResult {
        val gitService = GitService.getInstance(project)
        val remoteUrl = gitService.getRemoteUrl()
            ?: return PRCreateResult(false, error = "No remote URL found")

        val repoInfo = parseRemoteUrl(remoteUrl)
            ?: return PRCreateResult(false, error = "Could not parse remote URL: $remoteUrl")

        return when (repoInfo.platform) {
            GitPlatform.BITBUCKET -> createBitbucketPR(repoInfo, title, description, sourceBranch, targetBranch, reviewers)
            GitPlatform.GITHUB -> createGitHubPR(repoInfo, title, description, sourceBranch, targetBranch, reviewers)
            GitPlatform.GITLAB -> createGitLabPR(repoInfo, title, description, sourceBranch, targetBranch, reviewers)
            GitPlatform.UNKNOWN -> PRCreateResult(false, error = "Unsupported git platform")
        }
    }

    private fun createBitbucketPR(
        repoInfo: RepoInfo,
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String,
        reviewers: List<Reviewer>
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
            if (reviewers.isNotEmpty()) {
                val reviewersArray = com.google.gson.JsonArray()
                reviewers.forEach { reviewer ->
                    reviewersArray.add(JsonObject().apply {
                        // If ID looks like a UUID (starts with {), use uuid field
                        // Otherwise use account_id (for manually entered usernames)
                        if (reviewer.id.startsWith("{")) {
                            addProperty("uuid", reviewer.id)
                        } else {
                            addProperty("account_id", reviewer.id)
                        }
                    })
                }
                add("reviewers", reviewersArray)
            }
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
        targetBranch: String,
        reviewers: List<Reviewer>
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
                val prNumber = jsonResponse.get("number")?.asInt

                // Add reviewers via separate API call
                if (reviewers.isNotEmpty() && prNumber != null) {
                    addGitHubReviewers(repoInfo, prNumber, reviewers, token)
                }

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

    private fun addGitHubReviewers(repoInfo: RepoInfo, prNumber: Int, reviewers: List<Reviewer>, token: String) {
        val apiUrl = "https://api.github.com/repos/${repoInfo.owner}/${repoInfo.repo}/pulls/$prNumber/requested_reviewers"

        try {
            val requestBody = JsonObject().apply {
                val reviewersArray = com.google.gson.JsonArray()
                reviewers.forEach { reviewer ->
                    reviewersArray.add(reviewer.username)
                }
                add("reviewers", reviewersArray)
            }

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
            if (responseCode != HttpURLConnection.HTTP_CREATED && responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn("Failed to add GitHub reviewers: $responseCode")
            }
        } catch (e: Exception) {
            logger.warn("Failed to add GitHub reviewers", e)
        }
    }

    private fun createGitLabPR(
        repoInfo: RepoInfo,
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String,
        reviewers: List<Reviewer>
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
            if (reviewers.isNotEmpty()) {
                val reviewerIds = com.google.gson.JsonArray()
                reviewers.forEach { reviewer ->
                    reviewerIds.add(reviewer.id.toIntOrNull() ?: 0)
                }
                add("reviewer_ids", reviewerIds)
            }
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
