package com.heddrich.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** Vom Nutzer waehlbarer Themenmodus. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromString(v: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(v, true) } ?: SYSTEM
    }
}

/**
 * App-Thema: deterministisch ueber ColorScheme-Auswahl (kein
 * AppCompat-NightMode/Recreation-Mechanik). SYSTEM folgt dem Geraet.
 */
@Composable
fun CompanionTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
