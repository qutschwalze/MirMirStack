package com.heddrich.companion.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.heddrich.companion.bookstack.BookStackClient
import com.heddrich.companion.llm.LlmClient
import com.heddrich.companion.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einstellungen: BookStack-Verbindung, LLM (OpenAI-kompatibel) und Darstellung.
 * Alle Werte verschluesselt (EncryptedSharedPreferences).
 */
@Composable
fun SettingsRoute(
    onThemeChanged: (ThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.Holder.get(context.applicationContext) }

    var url by remember { mutableStateOf(settings.bookstackUrl) }
    var tokenId by remember { mutableStateOf(settings.bookstackTokenId) }
    var tokenSecret by remember { mutableStateOf(settings.bookstackTokenSecret) }
    var bookId by remember { mutableStateOf(settings.targetBookId.toString()) }
    var configPage by remember { mutableStateOf(settings.configPageId.toString()) }

    var llmUrl by remember { mutableStateOf(settings.llmBaseUrl) }
    var llmKey by remember { mutableStateOf(settings.llmApiKey) }
    var llmModel by remember { mutableStateOf(settings.llmModel) }

    var theme by remember { mutableStateOf(ThemeMode.fromString(settings.themeMode)) }

    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var llmTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persistBookStack() {
        settings.bookstackUrl = url
        settings.bookstackTokenId = tokenId
        settings.bookstackTokenSecret = tokenSecret
        settings.targetBookId = bookId.toIntOrNull() ?: 3
        settings.configPageId = configPage.toIntOrNull() ?: 0
    }

    fun persistLlm() {
        settings.llmBaseUrl = llmUrl
        settings.llmApiKey = llmKey
        settings.llmModel = llmModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── BookStack ──────────────────────────────────────────────────────
        Text("BookStack", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server-URL (z. B. wiki.example.com)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tokenId,
            onValueChange = { tokenId = it },
            label = { Text("API Token-ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tokenSecret,
            onValueChange = { tokenSecret = it },
            label = { Text("API Token Secret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = bookId,
            onValueChange = { bookId = it.filter(Char::isDigit).take(4) },
            label = { Text("Ziel-Buch-ID (3 = Meetings und Notizen)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = configPage,
            onValueChange = { configPage = it.filter(Char::isDigit).take(6) },
            label = { Text("Vorlagen-Konfig-Seite ID (0 = eingebaute Vorlagen)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                persistBookStack()
                testing = true
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            "OK – " + BookStackClient.testConnection(settings)
                        } catch (e: retrofit2.HttpException) {
                            val body = try {
                                e.response()?.errorBody()?.string().orEmpty().take(220)
                            } catch (_: Exception) { "" }
                            "HTTP ${e.code()}: ${body.ifBlank { "(kein Body)" }}\n" +
                                    "URL: ${BookStackClient.normalizeBaseUrl(settings.bookstackUrl)}"
                        } catch (e: Exception) {
                            "FEHLER: ${e.message ?: e.javaClass.simpleName}\n" +
                                    "URL: ${BookStackClient.normalizeBaseUrl(settings.bookstackUrl)}"
                        }
                    }
                    testing = false
                }
            }) {
                Text(if (testing) "Teste…" else "Speichern + Verbindung testen")
            }
            OutlinedButton(onClick = {
                persistBookStack()
                message = "Gespeichert."
            }) {
                Text("Nur speichern")
            }
        }

        HorizontalDivider()

        // ── LLM ────────────────────────────────────────────────────────────
        Text("KI-Zusammenfassung (OpenAI-kompatibel)", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = llmUrl,
            onValueChange = { llmUrl = it },
            label = { Text("Basis-URL (z. B. https://.../v1/)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = llmKey,
            onValueChange = { llmKey = it },
            label = { Text("API-Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = llmModel,
            onValueChange = { llmModel = it },
            label = { Text("Modellname (z. B. gemini-2.0-flash)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                persistLlm()
                llmTesting = true
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            val answer = LlmClient(llmUrl, llmKey)
                                .complete(llmModel, "Antworte mit einem Wort.", "Sag: OK")
                            "LLM OK – Antwort: ${answer.take(60)}"
                        } catch (e: retrofit2.HttpException) {
                            "LLM HTTP ${e.code()} – URL/Key/Modell prüfen"
                        } catch (e: Exception) {
                            "LLM FEHLER: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                    llmTesting = false
                }
            }) {
                Text(if (llmTesting) "Teste…" else "KI testen")
            }
            OutlinedButton(onClick = {
                persistLlm()
                message = "Gespeichert."
            }) {
                Text("Nur speichern")
            }
        }

        HorizontalDivider()

        // ── Darstellung ────────────────────────────────────────────────────
        Text("Darstellung", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ThemeMode.LIGHT to "Hell",
                ThemeMode.SYSTEM to "System",
                ThemeMode.DARK to "Dunkel"
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = theme == mode,
                    onClick = {
                        theme = mode
                        settings.themeMode = mode.name
                        onThemeChanged(mode)
                    },
                    label = { Text(label) }
                )
            }
        }

        HorizontalDivider()

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("OK") || it.startsWith("LLM OK"))
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }

        Text(
            "Hinweis: BookStack-Token unter Profil → API-Token erstellen. " +
                    "LLM-Basis-URL muss den OpenAI-Pfad enthalten (z. B. …/v1/). " +
                    "Alle Werte werden verschlüsselt auf dem Gerät gespeichert.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
