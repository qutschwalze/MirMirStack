package com.heddrich.companion.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single Source of Truth fuer alle Verbindungs-Einstellungen
 * (Sherpa-Pattern: URL single source of truth).
 *
 * Speicherung verschluesselt via EncryptedSharedPreferences.
 * Lesezugriff ist synchron (SharedPreferences) – Werte sind klein.
 */
class SettingsStore private constructor(context: Context) {

    val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "companion_settings",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ── BookStack ──────────────────────────────────────────────────────────

    var bookstackUrl: String
        get() = prefs.getString(K_BS_URL, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_BS_URL, v.trim()).apply()

    var bookstackTokenId: String
        get() = prefs.getString(K_BS_TOKEN_ID, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_BS_TOKEN_ID, v.trim()).apply()

    var bookstackTokenSecret: String
        get() = prefs.getString(K_BS_TOKEN_SECRET, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_BS_TOKEN_SECRET, v.trim()).apply()

    var targetBookId: Int
        get() = prefs.getInt(K_TARGET_BOOK, DEFAULT_BOOK_ID)
        set(v) = prefs.edit().putInt(K_TARGET_BOOK, v).apply()

    // ── LLM (OpenAI-kompatibel) ────────────────────────────────────────────

    var llmBaseUrl: String
        get() = prefs.getString(K_LLM_URL, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_LLM_URL, v.trim()).apply()

    var llmApiKey: String
        get() = prefs.getString(K_LLM_KEY, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_LLM_KEY, v.trim()).apply()

    var llmModel: String
        get() = prefs.getString(K_LLM_MODEL, "").orEmpty().trim()
        set(v) = prefs.edit().putString(K_LLM_MODEL, v.trim()).apply()

    /** Konfiguration vollstaendig? Sonst faellt die Pipeline auf Rohtext zurueck. */
    val isLlmConfigured: Boolean
        get() = llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank() && llmModel.isNotBlank()

    // ── Darstellung ────────────────────────────────────────────────────────

    var themeMode: String
        get() = prefs.getString(K_THEME, "SYSTEM").orEmpty()
        set(v) = prefs.edit().putString(K_THEME, v).apply()

    // ── Vorlagen-Konfigurationsseite (Phase 4) ─────────────────────────────

    /** Wiki-Seiten-ID der privaten Vorlagen-Konfiguration (0 = aus). */
    var configPageId: Int
        get() = prefs.getInt(K_CONFIG_PAGE, 0)
        set(v) = prefs.edit().putInt(K_CONFIG_PAGE, v).apply()

    /** Konfiguration vollstaendig? (Publish-Worker bricht sonst mit klarer Meldung ab.) */
    val isConfigured: Boolean
        get() = bookstackUrl.isNotBlank() &&
                bookstackTokenId.isNotBlank() &&
                bookstackTokenSecret.isNotBlank()

    // ── Keys ───────────────────────────────────────────────────────────────

    private companion object {
        const val K_BS_URL = "bookstack_url"
        const val K_BS_TOKEN_ID = "bookstack_token_id"
        const val K_BS_TOKEN_SECRET = "bookstack_token_secret"
        const val K_TARGET_BOOK = "target_book_id"
        const val K_LLM_URL = "llm_base_url"
        const val K_LLM_KEY = "llm_api_key"
        const val K_LLM_MODEL = "llm_model"
        const val K_THEME = "theme_mode"
        const val K_CONFIG_PAGE = "config_page_id"

        /** Buch 3 = „Meetings und Notizen" laut Wiki-Struktur. */
        const val DEFAULT_BOOK_ID = 3
    }

    object Holder {
        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
