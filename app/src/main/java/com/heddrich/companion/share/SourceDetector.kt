package com.heddrich.companion.share

/**
 * Art der Quelle eines geteilten Inhalts.
 * Primäres Signal: Referrer (vom System gestempelt), Fallback: Inhaltsheuristik.
 */
enum class SourceKind {
    SHERPA,
    BROWSER,
    WHATSAPP,
    EMAIL,
    FILES,
    OTHER_APP,
    UNKNOWN
}

object SourceDetector {

    private const val SHERPA_PACKAGE = "com.sherpa.transcript"

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fennec_fdroid",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser",
        "org.torproject.torbrowser"
    )

    // Mail-Apps: explizite Liste + heuristischer Fallback auf Paketnamen mit "mail"
    private val EMAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.samsung.android.email.provider",
        "com.blue.mail.gm3",
        "de.eplus.mailcom.client"
    )

    /** Sprecher-Marker wie "Sprecher A:", "Speaker 2:", "SPK B:" am Zeilenanfang. */
    private val SPEAKER_MARKER = Regex("(?im)^\\s*(Sprecher|Speaker|SPK)\\s+[A-F0-9]\\s*:")

    /**
     * Detektiert die Quelle. Reihenfolge:
     * 1. Referrer-Host (verlaesslich, vom Sharesheet gesetzt)
     * 2. Inhaltsheuristik (wenn kein Referrer vorliegt)
     */
    fun detect(referrerHost: String?, textPreview: String?): SourceKind {
        if (!referrerHost.isNullOrBlank()) return fromPackage(referrerHost)
        return heuristic(textPreview.orEmpty())
    }

    fun fromPackage(pkg: String): SourceKind = when {
        pkg == SHERPA_PACKAGE -> SourceKind.SHERPA
        pkg.startsWith("com.whatsapp") -> SourceKind.WHATSAPP
        pkg in BROWSER_PACKAGES -> SourceKind.BROWSER
        pkg in EMAIL_PACKAGES || pkg.contains("mail") -> SourceKind.EMAIL
        else -> SourceKind.OTHER_APP
    }

    internal fun heuristic(text: String): SourceKind {
        val t = text.trim()
        if (t.isEmpty()) return SourceKind.UNKNOWN
        // Transkript-Erkennung zuerst (Sprecher-Marker sind sehr spezifisch)
        if (SPEAKER_MARKER.containsMatchIn(t.take(600))) return SourceKind.SHERPA
        // JSON-Exporte: Struktur erkennbar, Quelle aber unklar
        if ((t.startsWith("{") && t.endsWith("}")) ||
            (t.startsWith("[") && t.endsWith("]"))
        ) return SourceKind.UNKNOWN
        // Fast nur URLs -> aus einem Browser geteilt
        if (isMostlyUrls(t)) return SourceKind.BROWSER
        return SourceKind.UNKNOWN
    }

    private fun isMostlyUrls(t: String): Boolean {
        val lines = t.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val urlLines = lines.count {
            val l = it.trim()
            l.startsWith("http://") || l.startsWith("https://")
        }
        return urlLines > 0 && urlLines >= lines.size - 1
    }
}
