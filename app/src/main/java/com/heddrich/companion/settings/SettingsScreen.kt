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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einstellungen (Phase 2): BookStack-URL, API-Token-ID/-Secret, Ziel-Buch-ID.
 * Speichern schreibt verschluesselt; „Verbindung testen" prueft live gegen die API.
 */
@Composable
fun SettingsRoute() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.Holder.get(context.applicationContext) }

    var url by remember { mutableStateOf(settings.bookstackUrl) }
    var tokenId by remember { mutableStateOf(settings.bookstackTokenId) }
    var tokenSecret by remember { mutableStateOf(settings.bookstackTokenSecret) }
    var bookId by remember { mutableStateOf(settings.targetBookId.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist() {
        settings.bookstackUrl = url
        settings.bookstackTokenId = tokenId
        settings.bookstackTokenSecret = tokenSecret
        settings.targetBookId = bookId.toIntOrNull() ?: 3
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                persist()
                testing = true
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        try {
                            "OK – " + BookStackClient.testConnection(settings)
                        } catch (e: retrofit2.HttpException) {
                            // Response-Body zeigen: unterscheidet BookStack-Berechtigungs-
                            // fehler (JSON) von Proxy-/WAF-Blockierungen (HTML)
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
                persist()
                message = "Gespeichert."
            }) {
                Text("Nur speichern")
            }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("OK")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }

        Text(
            "Hinweis: Token in BookStack unter Profil → API-Token erstellen. " +
                    "Alle Werte werden verschlüsselt auf dem Gerät gespeichert.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
