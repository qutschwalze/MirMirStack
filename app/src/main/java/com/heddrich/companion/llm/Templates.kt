package com.heddrich.companion.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Eingebaute Vorlagen (Phase 3). Phase 4 ergaenzt Wiki-getriebene Config;
 * Struktur (id, name, prompt) ist bereits darauf ausgelegt.
 */
data class Template(
    val id: String,
    val displayName: String,
    val systemPrompt: String
)

object Templates {
    val MEETING = Template(
        id = "meeting",
        displayName = "Meeting-Protokoll",
        systemPrompt = """Du erstellst praezise Meeting-Protokolle auf Deutsch.
Antworte AUSSCHLIESSLICH mit einem JSON-Objekt mit genau diesen Feldern:
{"title": string (kurze praegnante Titel, max 60 Zeichen, keine DatumPraefixe),
 "summary_md": string (Markdown-Zusammenfassung: Absaetze, Listen erlaubt),
 "decisions": string[] (getroffene Entscheidungen; leer falls keine),
 "todos": string[] (Aufgaben mit Verantwortlichen falls erkennbar),
 "participants": string[] (genannte Teilnehmer/Namen),
 "tags": string[] (2-5 thematische Tags)}
Kein Markdown-Codeblock, kein Text ausserhalb des JSON."""
    )

    val RESEARCH = Template(
        id = "research",
        displayName = "Recherche-Clip",
        systemPrompt = """Du erstellst Recherche-Zusammenfassungen auf Deutsch.
Antworte AUSSCHLIESSLICH mit einem JSON-Objekt mit genau diesen Feldern:
{"title": string (kurzer Titel, max 60 Zeichen),
 "summary_md": string (Kernpunkte als Markdown, Quellen/URLs erhalten),
 "decisions": [],
 "todos": string[] (offene Punkte zum Nachrecherchieren),
 "participants": [],
 "tags": string[] (2-5 thematische Tags)}
Kein Markdown-Codeblock, kein Text ausserhalb des JSON."""
    )

    val CHAT = Template(
        id = "chat",
        displayName = "Chat-Digest",
        systemPrompt = """Du erstellst kompakte Zusammenfassungen von Chatverlaeufen auf Deutsch.
Antworte AUSSCHLIESSLICH mit einem JSON-Objekt mit genau diesen Feldern:
{"title": string (kurzer Titel, max 60 Zeichen),
 "summary_md": string (Wesentliche Punkte/Vereinbarungen als Markdown),
 "decisions": string[] (Vereinbarungen),
 "todos": string[] (zugesagte Aufgaben),
 "participants": string[] (Beteiligte falls erkennbar),
 "tags": string[] (2-5 thematische Tags)}
Kein Markdown-Codeblock, kein Text ausserhalb des JSON."""
    )

    val UNIVERSAL = Template(
        id = "universal",
        displayName = "Universal",
        systemPrompt = """Du klassifizierst den folgenden Inhalt (Meeting-Transkript,
Recherche-Text oder Chatverlauf) und erstellst eine passende Zusammenfassung auf Deutsch.
Antworte AUSSCHLIESSLICH mit einem JSON-Objekt mit genau diesen Feldern:
{"title": string (kurzer Titel, max 60 Zeichen),
 "summary_md": string (strukturierte Zusammenfassung als Markdown),
 "decisions": string[],
 "todos": string[],
 "participants": string[],
 "tags": string[]}
Kein Markdown-Codeblock, kein Text ausserhalb des JSON."""
    )

    fun byId(id: String?): Template = when (id) {
        MEETING.id -> MEETING
        RESEARCH.id -> RESEARCH
        CHAT.id -> CHAT
        else -> UNIVERSAL
    }

    /** Vorauswahl nach erkannter Quelle (Routing-Heuristik). */
    fun defaultFor(sourceKind: String): String = when (sourceKind) {
        "SHERPA" -> MEETING.id
        "BROWSER" -> RESEARCH.id
        "WHATSAPP", "EMAIL" -> CHAT.id
        else -> UNIVERSAL.id
    }

    fun all(): List<Template> = listOf(MEETING, RESEARCH, CHAT, UNIVERSAL)
}

/**
 * Toleranter Parser fuer LLM-Antworten: akzeptiert sauberes JSON wie auch
 * Codefence-Eingaben ("```json ... ```") und extrahiert das erste JSON-Objekt.
 * Pflichtfelder werden validiert; Fehlen fuehrt zu einer klaren Fehlermeldung.
 */
object SummaryParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class RawSummary(
        val title: String? = null,
        @kotlinx.serialization.SerialName("summary_md") val summaryMd: String? = null,
        val decisions: List<String> = emptyList(),
        val todos: List<String> = emptyList(),
        val participants: List<String> = emptyList(),
        val tags: List<String> = emptyList()
    )

    fun parse(raw: String): SummaryResult {
        val cleaned = stripCodeFence(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException("Antwort enthaelt kein JSON-Objekt")
        }
        val candidate = cleaned.substring(start, end + 1)
        val parsed = try {
            json.decodeFromString<RawSummary>(candidate)
        } catch (e: Exception) {
            throw IllegalStateException("JSON ungueltig: ${e.message?.take(120)}")
        }
        val title = parsed.title?.trim().orEmpty()
        val summary = parsed.summaryMd?.trim().orEmpty()
        if (title.isEmpty() || summary.isEmpty()) {
            throw IllegalStateException("Pflichtfelder fehlen (title/summary_md)")
        }
        return SummaryResult(
            title = title.take(80),
            summaryMd = summary,
            decisions = parsed.decisions.filter { it.isNotBlank() },
            todos = parsed.todos.filter { it.isNotBlank() },
            participants = parsed.participants.map { it.trim() }.filter { it.isNotEmpty() },
            tags = parsed.tags.map { it.trim().lowercase().replace(' ', '-') }
                .filter { it.isNotEmpty() }.distinct()
        )
    }

    internal fun stripCodeFence(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val fenceEnd = t.lastIndexOf("```")
            if (fenceEnd >= 0) t = t.substring(0, fenceEnd)
        }
        return t.trim()
    }
}
