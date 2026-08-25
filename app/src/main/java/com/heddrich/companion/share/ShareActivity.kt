package com.heddrich.companion.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_INGEST_CHARS = 2_000_000

/**
 * Empfaengt geteilte Inhalte (ACTION_SEND / ACTION_SEND_MULTIPLE),
 * persistiert sie SOFORT verlustfrei in der Outbox und zeigt dann
 * Vorschau + Vorlagenwahl.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val referrerHost = referrer?.host
        val received = extractFromIntent(intent)

        setContent {
            MaterialTheme {
                ShareScreen(
                    preview = received.preview,
                    mime = received.mime,
                    rawUri = received.rawUri,
                    sourcePkg = received.sourcePkg ?: referrerHost,
                    sourceKindHint = received.sourceKindHint,
                    referrerHost = referrerHost
                )
            }
        }
    }

    private data class Received(
        val preview: String?,
        val mime: String?,
        val rawUri: String?,
        val sourcePkg: String?,
        val sourceKindHint: com.heddrich.companion.share.SourceKind?
    )

    private fun extractFromIntent(intent: Intent?): Received {
        if (intent == null) return Received(null, null, null, null, null)

        var text: String? = null
        var uri: Uri? = null
        var mime: String? = intent.type

        when (intent.action) {
            Intent.ACTION_SEND -> {
                text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri ?: uri
                // Text auch aus der ClipDescription/daten holen, falls EXTRA_TEXT fehlt
                if (text == null && uri != null) {
                    text = readTextFromUri(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    uri = uris.first() // Phase 1: erste Datei; Mehrfachauswahl kommt spaeter
                    text = readTextFromUri(uri)
                }
            }
        }
        if (text != null && text.length > MAX_INGEST_CHARS) {
            text = text.substring(0, MAX_INGEST_CHARS)
        }
        return Received(
            preview = text,
            mime = mime,
            rawUri = uri?.toString(),
            sourcePkg = null, // wird vom Referrer bzw. URI-Authority abgeleitet
            sourceKindHint = null
        )
    }

    private fun readTextFromUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    preview: String?,
    mime: String?,
    rawUri: String?,
    sourcePkg: String?,
    sourceKindHint: com.heddrich.companion.share.SourceKind?,
    referrerHost: String?
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Quelle einmalig beim ersten Compose detektieren und Item sofort speichern
    var savedId by remember { mutableStateOf<Long?>(null) }
    var detected by remember { mutableStateOf<com.heddrich.companion.share.SourceKind>(
        com.heddrich.companion.share.SourceKind.UNKNOWN
    ) }

    LaunchedEffect(preview, referrerHost) {
        detected = SourceDetector.detect(referrerHost, preview?.take(600))
        if (savedId == null && !preview.isNullOrBlank()) {
            val item = IngestItem(
                createdAt = System.currentTimeMillis(),
                sourcePkg = sourcePkg ?: rawUriAuthority(rawUri),
                sourceKind = detected,
                templateId = null,
                title = suggestTitle(preview),
                rawText = preview,
                rawUri = rawUri,
                mime = mime,
                status = IngestStatus.QUEUED,
                error = null,
                resultUrl = null
            )
            savedId = withContext(Dispatchers.IO) {
                CompanionDatabase.get(context).ingestItemDao().insert(item)
            }
        }
    }

    var title by remember(savedId) { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Neuer Eintrag") },
                actions = {
                    IconButton(onClick = { context.closeActivity() }) {
                        Icon(Icons.Default.Close, contentDescription = "Abbrechen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AssistChip(
                onClick = {},
                label = { Text(sourceLabel(detected)) }
            )
            Text(
                "Vorschau",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                preview?.take(400)?.ifBlank { "(kein Text empfangen)" } ?: "(kein Text empfangen)",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
            OutlinedTextField(
                value = title ?: suggestTitle(preview.orEmpty()),
                onValueChange = { title = it },
                label = { Text("Titel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val id = savedId ?: return@Button
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val dao = CompanionDatabase.get(context).ingestItemDao()
                                dao.getById(id)?.let { dao.update(it.copy(title = title)) }
                            }
                            context.closeActivity()
                        }
                    },
                    enabled = savedId != null
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text("Speichern")
                }
                OutlinedButton(onClick = { context.closeActivity() }) {
                    Text("Spaeter")
                }
            }
            Text(
                "Gespeichert in der lokalen Outbox – Verarbeitung folgt in Phase 2/3.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun rawUriAuthority(uriString: String?): String? =
    uriString?.let { runCatching { android.net.Uri.parse(it).authority }.getOrNull() }

private fun android.content.Context.closeActivity() {
    (this as? android.app.Activity)?.finishAffinity()
}

private fun suggestTitle(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        firstLine.isEmpty() -> "Eintrag ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        firstLine.length <= 60 -> firstLine
        else -> firstLine.take(57) + "..."
    }
}

private fun sourceLabel(kind: com.heddrich.companion.share.SourceKind): String = when (kind) {
    com.heddrich.companion.share.SourceKind.SHERPA -> "Quelle: Sherpa Transcript"
    com.heddrich.companion.share.SourceKind.BROWSER -> "Quelle: Browser"
    com.heddrich.companion.share.SourceKind.WHATSAPP -> "Quelle: WhatsApp"
    com.heddrich.companion.share.SourceKind.EMAIL -> "Quelle: E-Mail"
    com.heddrich.companion.share.SourceKind.FILES -> "Quelle: Dateien"
    com.heddrich.companion.share.SourceKind.OTHER_APP -> "Quelle: andere App"
    com.heddrich.companion.share.SourceKind.UNKNOWN -> "Quelle: unbekannt"
}
