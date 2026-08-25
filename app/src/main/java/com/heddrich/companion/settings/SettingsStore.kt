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
