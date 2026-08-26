package com.heddrich.companion.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wiki-getriebene Vorlagen (Phase 4): Eine private Wiki-Seite enthaelt ein
 * JSON-Array von Vorlagen. Wiki-Vorlagen mit gleicher id ueberschreiben die
 * eingebauten Defaults; Parsefehler fuehren zu Defaults + Warnung (niemals Crash).
 *
 * Format auf der Wiki-Seite (im HTML als <p>```json ... ```</p> oder frei):
 * [
 *   {"id":"meeting","name":"Meeting (mein Stil)","prompt":"...","tags":["typ=meeting"]}
 * ]
 */
object WikiTemplateLoader {

    @Serializable
    private data class RawWikiTemplate(
        val id: String? = null,
        val name: String? = null,
        val prompt: String? = null,
        val tags: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Liefert die effektive Vorlagenliste: Defaults + Wiki-Merge. */
    fun merge(defaults: List<Template>, wikiHtml: String?): Pair<List<Template>, String?> {
        if (wikiHtml.isNullOrBlank()) return defaults to null
        val rawJson = extractJson(wikiHtml) ?: return defaults to
                "Konfigurationsseite enthaelt kein JSON – eingebaute Vorlagen aktiv."
        val parsed = try {
            json.decodeFromString<List<RawWikiTemplate>>(rawJson)
        } catch (e: Exception) {
            return defaults to "Vorlagen-JSON ungueltig (${e.message?.take(80)}) – eingebaute aktiv."
        }
        if (parsed.isEmpty()) return defaults to null

        var warning: String? = null
        val merged = defaults.mapNotNull { d ->
            val override = parsed.firstOrNull { it.id == d.id }
            when {
                override == null -> d
                override.prompt.isNullOrBlank() -> {
                    warning = "Vorlage '${d.id}' hat leeren prompt – Default aktiv."
                    d
                }
                else -> d.copy(
                    displayName = override.name?.takeIf { it.isNotBlank() } ?: d.displayName,
                    systemPrompt = override.prompt,
                    defaultTags = override.tags.ifEmpty { d.defaultTags }
                )
            }
        }.toMutableList()

        // Neue Vorlagen aus dem Wiki anhaengen
        for (w in parsed) {
            val id = w.id?.trim().orEmpty()
            if (id.isEmpty() || w.prompt.isNullOrBlank()) continue
            if (merged.any { it.id == id }) continue
            merged.add(
                Template(
                    id = id,
                    displayName = w.name?.takeIf { it.isNotBlank() } ?: id,
                    systemPrompt = w.prompt,
                    defaultTags = w.tags
                )
            )
        }
        return merged to warning
    }

    /** Extrahiert das JSON-Array aus HTML (Codefence bevorzugt, sonst erstes [ … ]). */
    internal fun extractJson(html: String): String? {
        // HTML-Tags entfernen
        val text = html.replace(Regex("<[^>]+>"), "").trim()
        Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)?.let { return it.groupValues[1].trim() }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start in 0 until end) return text.substring(start, end + 1).trim()
        // Fallback: einzelnes Objekt erlaubt
        val os = text.indexOf('{')
        val oe = text.lastIndexOf('}')
        if (os in 0 until oe) return "[${text.substring(os, oe + 1)}]"
        return null
    }
}
