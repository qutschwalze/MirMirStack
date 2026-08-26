package com.heddrich.companion.llm

import android.content.Context
import com.heddrich.companion.bookstack.BookStackClient
import com.heddrich.companion.settings.SettingsStore

/**
 * Cache fuer die effektive Vorlagenliste (Defaults + Wiki-Merge).
 * Wird beim App-Start und vor jedem Verarbeitungslauf aktualisiert;
 * laedt fehlgeschlagen, bleiben die letzten bekannten Werte aktiv.
 */
object TemplateCache {

    @Volatile
    var templates: List<Template> = Templates.all()
        private set

    @Volatile
    var lastWarning: String? = null
        private set

    fun find(id: String?): Template =
        templates.firstOrNull { it.id == id }
            ?: templates.firstOrNull { it.id == Templates.UNIVERSAL.id }
            ?: Templates.UNIVERSAL

    /**
     * Laedt die Vorlagen-Konfiguration aus dem Wiki (wenn konfiguriert)
     * und mergt sie ueber die Defaults. Wirft nicht.
     */
    suspend fun refresh(context: Context) {
        try {
            val settings = SettingsStore.Holder.get(context.applicationContext)
            if (!settings.isConfigured || settings.configPageId <= 0) return
            val html = BookStackClient.fromSettings(settings).pageHtml(settings.configPageId)
            val (merged, warning) = WikiTemplateLoader.merge(Templates.all(), html)
            templates = merged
            lastWarning = warning
        } catch (_: Throwable) {
            // Netzwerk-/Auth-Fehler: bestehender Cache bleibt aktiv
        }
    }
}
