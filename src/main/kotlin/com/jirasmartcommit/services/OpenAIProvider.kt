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
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 1024
)

@Serializable
data class OpenAIMessage(
    val role: String,
    val content: String
)

@Serializable
data class OpenAIResponse(
    val choices: List<OpenAIChoice>? = null,
    val error: OpenAIError? = null
)

@Serializable
data class OpenAIChoice(
    val message: OpenAIMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenAIError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

class OpenAIProvider : AIProviderInterface {

    private val logger = Logger.getInstance(OpenAIProvider::class.java)

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

        val requestBody = OpenAIRequest(
            model = model,
            messages = listOf(
                OpenAIMessage(role = "system", content = systemPrompt),
                OpenAIMessage(role = "user", content = userPrompt)
            )
        )

        val requestJson = json.encodeToString(OpenAIRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val error = try {
                    responseBody?.let { json.decodeFromString<OpenAIResponse>(it) }?.error
                } catch (e: Exception) {
                    null
                }

                val errorMessage = when (response.code) {
                    401 -> "Invalid OpenAI API key. Please check your credentials."
                    429 -> "OpenAI API rate limit exceeded. Please try again later."
                    500, 502, 503 -> "OpenAI service is temporarily unavailable. Please try again."
                    else -> error?.message ?: "OpenAI API error: HTTP ${response.code}"
                }

                throw AIProviderException(errorMessage)
            }

            if (responseBody == null) {
                throw AIProviderException("Empty response from OpenAI")
            }

            val openAIResponse = json.decodeFromString<OpenAIResponse>(responseBody)

            val content = openAIResponse.choices?.firstOrNull()?.message?.content
                ?: throw AIProviderException("No content in OpenAI response")

            logger.info("Successfully generated response using OpenAI model: $model")
            return content

        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            logger.error("OpenAI API call failed", e)
            throw AIProviderException("Failed to connect to OpenAI: ${e.message}", e)
        }
    }

    companion object {
        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }
}
