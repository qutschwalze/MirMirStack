package com.heddrich.companion.llm

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface OpenAiCompatApi {
    /** Pfad relativ zur Base-URL; Basis inkl. /v1/ vom Nutzer angegeben. */
    @POST("chat/completions")
    suspend fun chatCompletions(@Body body: ChatRequest): ChatResponse
}

/**
 * Minimaler OpenAI-kompatibler Client fuer Zusammenfassungen.
 * Grosszuegige Timeouts (LLM-Antworten dauern 10-60 s), Retry macht der Aufrufer.
 */
class LlmClient(
    baseUrl: String,
    apiKey: String,
    timeoutSeconds: Long = 120
) {
    private val api: OpenAiCompatApi

    init {
        require(baseUrl.isNotBlank()) { "LLM-Basis-URL fehlt" }
        require(apiKey.isNotBlank()) { "LLM-API-Key fehlt" }

        val auth = Interceptor0(apiKey)
        val http = OkHttpClient.Builder()
            .addInterceptor(auth)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        api = Retrofit.Builder()
            .baseUrl(withTrailingSlash(baseUrl))
            .client(http)
            .addConverterFactory(
                Json { ignoreUnknownKeys = true; explicitNulls = false }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(OpenAiCompatApi::class.java)
    }

    private class Interceptor0(private val key: String) : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            return chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .build()
            )
        }
    }

    /**
     * Fuehrt einen Zusammenfassungs-Call aus und liefert den Rohtext der Antwort
     * (erwartet wird ein JSON-Objekt als String; Parsing macht der SummaryParser).
     */
    suspend fun complete(model: String, systemPrompt: String, userContent: String): String {
        val response = api.chatCompletions(
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userContent.take(MAX_INPUT_CHARS))
                )
            )
        )
        return response.firstContent()
            ?: throw IllegalStateException("LLM lieferte keine Antwort (leere Choices)")
    }

    companion object {
        const val MAX_INPUT_CHARS = 100_000

        fun withTrailingSlash(url: String): String =
            url.trim().trimEnd('/').let { "$it/" }
    }
}
