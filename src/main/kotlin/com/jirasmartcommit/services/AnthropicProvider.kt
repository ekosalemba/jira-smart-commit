package com.jirasmartcommit.services

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class AnthropicRequest(
    val model: String,
    val system: String,
    val messages: List<AnthropicMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int = 1024
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicContent>? = null,
    val error: AnthropicError? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null
)

@Serializable
data class AnthropicContent(
    val type: String,
    val text: String? = null
)

@Serializable
data class AnthropicError(
    val type: String? = null,
    val message: String? = null
)

class AnthropicProvider : AIProviderInterface {

    private val logger = Logger.getInstance(AnthropicProvider::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        customEndpoint: String?
    ): String {
        val endpoint = customEndpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

        val requestBody = AnthropicRequest(
            model = model,
            system = systemPrompt,
            messages = listOf(
                AnthropicMessage(role = "user", content = userPrompt)
            )
        )

        val requestJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val error = try {
                    responseBody?.let { json.decodeFromString<AnthropicResponse>(it) }?.error
                } catch (e: Exception) {
                    null
                }

                val errorMessage = when (response.code) {
                    401 -> "Invalid Anthropic API key. Please check your credentials."
                    429 -> "Anthropic API rate limit exceeded. Please try again later."
                    500, 502, 503 -> "Anthropic service is temporarily unavailable. Please try again."
                    else -> error?.message ?: "Anthropic API error: HTTP ${response.code}"
                }

                throw AIProviderException(errorMessage)
            }

            if (responseBody == null) {
                throw AIProviderException("Empty response from Anthropic")
            }

            val anthropicResponse = json.decodeFromString<AnthropicResponse>(responseBody)

            val content = anthropicResponse.content
                ?.firstOrNull { it.type == "text" }
                ?.text
                ?: throw AIProviderException("No content in Anthropic response")

            logger.info("Successfully generated response using Anthropic model: $model")
            return content

        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            logger.error("Anthropic API call failed", e)
            throw AIProviderException("Failed to connect to Anthropic: ${e.message}", e)
        }
    }

    companion object {
        private const val DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
