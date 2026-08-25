package com.heddrich.companion.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI-kompatibles Chat-Protocol (funktioniert mit Gemini-OpenAI-Endpoint u. a.). */
@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String = "json_object")

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
    val temperature: Double = 0.2
)

@Serializable
data class ChatChoiceMessage(val role: String = "assistant", val content: String = "")

@Serializable
data class ChatChoice(val index: Int = 0, val message: ChatChoiceMessage = ChatChoiceMessage())

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList()
) {
    fun firstContent(): String? = choices.firstOrNull()?.message?.content
}

/**
 * Strukturiertes Zusammenfassungsergebnis (wird vom LLM als JSON geliefert
 * und von der App deterministisch zu HTML gerendert – nie LLM-Rohtml ins Wiki).
 */
data class SummaryResult(
    val title: String,
    val summaryMd: String,
    val decisions: List<String>,
    val todos: List<String>,
    val participants: List<String>,
    val tags: List<String>
)
