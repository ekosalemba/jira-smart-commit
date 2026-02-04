package com.jirasmartcommit.services

interface AIProviderInterface {
    suspend fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        customEndpoint: String? = null
    ): String
}

class AIProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
