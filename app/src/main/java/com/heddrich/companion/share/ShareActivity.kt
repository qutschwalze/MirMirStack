package com.heddrich.companion.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hartes Limit pro Element (Zeichen). Groessere Dateien werden gekappt und vermerkt. */
const val MAX_INGEST_CHARS = 2_000_000

/** Zustand des Empfangs: laedt → fertig oder fehlgeschlagen (mit Diagnose). */
sealed interface ShareLoadState {
    data object Loading : ShareLoadState
    data class Ready(
        val itemId: Long,
        val preview: String,
        val mime: String?,
        val rawUri: String?,
        val sourcePkg: String?,
        val sourceKind: SourceKind,
        val warning: String?
    ) : ShareLoadState
    data class Failed(val message: String, val stack: String, val persisted: Boolean) : ShareLoadState
}

/**
 * Empfaengt geteilte Inhalte (ACTION_SEND / ACTION_SEND_MULTIPLE).
 *
 * Design nach Feldbefund 0.2.0 („schliesst sich sofort beim Teilen"):
 * ALLES Riskante (Intent-Auswertung, Datei-Lesen, DB-Insert) laeuft ausserhalb
 * des Hauptthreads in einem einzigen abgesicherten Block. Jede Ausnahme wird
 * abgefangen, als FAILED-Eintrag inkl. Stacktrace in die Outbox geschrieben
 * und in der UI angezeigt – die App schliesst sich nie mehr kommentarlos.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Wiki-Vorlagen-Overrides beim Start frisch ziehen (fehler-tolerant)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.heddrich.companion.llm.TemplateCache.refresh(applicationContext)
        }

        val referrerHost = try {
            referrer?.host
        } catch (_: Exception) {
            null
        }
        val shareIntent = intent

        setContent {
            val appTheme = com.heddrich.companion.ui.ThemeMode.fromString(
                SettingsStore.Holder.get(applicationContext).themeMode
            )
            com.heddrich.companion.ui.CompanionTheme(appTheme) {
                ShareRoot(shareIntent, referrerHost)
            }
        }
    }
}

@Composable
fun ShareRoot(intent: Intent?, referrerHost: String?) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ShareLoadState>(ShareLoadState.Loading) }

    LaunchedEffect(intent) {
        state = withContext(Dispatchers.IO) {
            receiveSharedItem(context.applicationContext, intent, referrerHost)
        }
    }

    when (val s = state) {
        ShareLoadState.Loading -> LoadingView()
        is ShareLoadState.Failed -> FailureView(s)
        is ShareLoadState.Ready -> ShareEditor(s)
    }
}

/**
 * Gesamter Empfangspfad in EINER abgesicherten Funktion (IO-Kontext):
 * Extrahieren → Detektieren → Persistieren. Wirft sie etwas, landet eine
 * FAILED-Zeile mit Stacktrace in der Outbox und die UI zeigt den Fehler.
 */
suspend fun receiveSharedItem(
    appContext: Context,
    intent: Intent?,
    referrerHost: String?
): ShareLoadState {
    try {
        val extracted = extractFromIntent(appContext.contentResolver, intent)

        // PDF: Text extrahieren + Original byte-genau kopieren (Lossless-Prinzip;
        // Share-URI-Berechtigungen laufen ab, die lokale Kopie bleibt).
        var text = extracted.preview
        var localPath: String? = null
        if (extracted.isPdf && extracted.rawUri != null) {
            val pdfUri = android.net.Uri.parse(extracted.rawUri)
            localPath = PdfSupport.copyOriginal(appContext, pdfUri)
            if (text.isNullOrBlank()) {
                text = PdfSupport.extractText(appContext, pdfUri)
                if (text.isNullOrBlank()) {
                    return failState(
                        appContext = appContext,
                        referrerHost = referrerHost,
                        message = "PDF enthaelt keinen extrahierbaren Text (moeglicherweise ein Scan ohne Textebene).",
                        stack = "",
                        extracted = extracted
                    )
                }
            }
        }

        // Sammler-Modus: Unbekanntes Format -> Datei verlustfrei sichern
        if (extracted.isBinary && extracted.rawUri != null) {
            val binUri = android.net.Uri.parse(extracted.rawUri)
            val copyPath = PdfSupport.copyOriginal(appContext, binUri)
            if (copyPath == null) {
                return failState(
                    appContext = appContext,
                    referrerHost = referrerHost,
                    message = "Datei konnte nicht gespeichert werden.",
                    stack = "",
                    extracted = extracted
                )
            }
            val displayName = PdfSupport.displayNameOf(appContext, binUri)
                ?: java.io.File(copyPath).name
            val binItem = IngestItem(
                createdAt = System.currentTimeMillis(),
                sourcePkg = referrerHost,
                sourceKind = SourceDetector.detect(referrerHost, displayName),
                templateId = "universal",
                title = displayName,
                rawText = null,
                rawUri = null,
                mime = extracted.mime,
                status = IngestStatus.QUEUED,
                error = null,
                resultUrl = null,
                rawLocalPath = copyPath
            )
            val binId = CompanionDatabase.get(appContext).ingestItemDao().insert(binItem)
            return ShareLoadState.Ready(
                itemId = binId,
                preview = displayName,
                mime = extracted.mime,
                rawUri = null,
                sourcePkg = referrerHost,
                sourceKind = binItem.sourceKind,
                warning = "Unbekanntes Format – die Datei wird gespeichert und ins Wiki hochgeladen (ohne Zusammenfassung)."
            )
        }

        val pkg = referrerHost
            ?: extracted.rawUri?.let { runCatching { Uri.parse(it).authority }.getOrNull() }
        val kind = SourceDetector.detect(pkg, text?.take(600))

        val item = IngestItem(
            createdAt = System.currentTimeMillis(),
            sourcePkg = pkg,
            sourceKind = kind,
            // Vorauswahl nach Quelle: Sherpa->Meeting, Browser->Recherche, Chat->Digest
            templateId = com.heddrich.companion.llm.Templates.defaultFor(kind.name),
            title = suggestTitle(text.orEmpty()),
            rawText = text,
            rawUri = extracted.rawUri,
            mime = extracted.mime,
            status = IngestStatus.QUEUED,
            error = extracted.warning,
            resultUrl = null,
            rawLocalPath = localPath
        )
        val id = CompanionDatabase.get(appContext).ingestItemDao().insert(item)
        return ShareLoadState.Ready(
            itemId = id,
            preview = text.orEmpty(),
            mime = extracted.mime,
            rawUri = extracted.rawUri,
            sourcePkg = pkg,
            sourceKind = kind,
            warning = extracted.warning
        )
    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
        // Share-Screen frueh geschlossen -> kein FEHLER-Eintrag, nur sauber beenden.
        throw ce
    } catch (t: Throwable) {
        return failState(
            appContext, referrerHost,
            t.message ?: t.javaClass.simpleName,
            stack = t.stackTraceToString().take(1200),
            extracted = null
        )
    }
}

/** Baut den Failed-Zustand inkl. dauerhaftem FAILED-Eintrag in der Outbox. */
private suspend fun failState(
    appContext: Context,
    referrerHost: String?,
    message: String,
    stack: String,
    extracted: Extracted?
): ShareLoadState.Failed {
    val persisted = try {
        CompanionDatabase.get(appContext).ingestItemDao().insert(
            IngestItem(
                createdAt = System.currentTimeMillis(),
                sourcePkg = referrerHost,
                sourceKind = SourceDetector.detect(referrerHost, null),
                templateId = null,
                title = "FEHLER beim Empfang ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                }",
                rawText = null,
                rawUri = extracted?.rawUri,
                mime = extracted?.mime,
                status = IngestStatus.FAILED,
                error = listOfNotNull(message.take(300), stack.ifBlank { null })
                    .joinToString("\n").ifBlank { null },
                resultUrl = null
            )
        )
        true
    } catch (_: Throwable) {
        false
    }
    return ShareLoadState.Failed(message, stack, persisted)
}

internal data class Extracted(
    val preview: String?,
    val mime: String?,
    val rawUri: String?,
    val warning: String?,
    val isPdf: Boolean = false,
    val isBinary: Boolean = false
)

/** Erkennung: Ist der MIME-Typ bzw. die Dateiendung textartig? */
private fun isTextish(mime: String?, uri: Uri?): Boolean {
    if (mime?.startsWith("text/") == true) return true
    if (mime in setOf(
            "application/json", "application/xml", "application/x-yaml",
            "application/csv", "text/markdown"
        )
    ) return true
    val ext = uri?.toString()?.substringAfterLast('.', "")?.lowercase()
    return ext in setOf("md", "txt", "json", "csv", "xml", "yaml", "yml", "log", "tsv")
}

internal fun extractFromIntent(resolver: android.content.ContentResolver, intent: Intent?): Extracted {
    if (intent == null) return Extracted(null, null, null, null)
    var text: String? = null
    var uri: Uri? = null
    var mime: String? = intent.type
    var warning: String? = null
    var isPdf = false
    var isBinary = false

    when (intent.action) {
        Intent.ACTION_SEND -> {
            text = intent.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION")
            uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null && (mime?.contains("pdf", true) == true ||
                    uri.toString()?.endsWith(".pdf") == true)
            ) {
                isPdf = true // Text kommt spaeter aus der PDF-Extraktion
            } else if (uri != null && text == null && !isTextish(mime, uri)) {
                isBinary = true // Unbekanntes Format -> als Datei sammeln
            } else if (text == null && uri != null) {
                val limited = readTextLimited(resolver, uri)
                text = limited.text
                warning = limited.warning
            }
        }
        Intent.ACTION_PROCESS_TEXT -> {
            // Markierter Text aus dem System-Auswahlmenue (ohne Sharesheet-Umweg)
            text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            @Suppress("DEPRECATION")
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (!uris.isNullOrEmpty()) {
                uri = uris.first() // Phase 1: erste Datei genuegt
                if (mime?.contains("pdf", true) == true || uri.toString().endsWith(".pdf")) {
                    isPdf = true
                } else if (!isTextish(mime, uri)) {
                    isBinary = true
                } else {
                    val limited = readTextLimited(resolver, uri)
                    text = limited.text
                    warning = limited.warning
                }
            }
        }
    }
    if (!isPdf && text != null && text.length > MAX_INGEST_CHARS) {
        text = text.substring(0, MAX_INGEST_CHARS)
    }
    return Extracted(text, mime, uri?.toString(), warning, isPdf, isBinary)
}

/** Liest Text gestreamt mit Zeichenlimit (kein Voll-Laden grosser Dateien mehr). */
private fun readTextLimited(resolver: android.content.ContentResolver, uri: Uri): LimitedRead {
    return try {
        resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val buf = StringBuilder()
            val cbuf = CharArray(8192)
            var read: Int
            var truncated = false
            while (reader.read(cbuf).also { read = it } > 0) {
                if (buf.length + read >= MAX_INGEST_CHARS) {
                    buf.append(cbuf, 0, MAX_INGEST_CHARS - buf.length)
                    truncated = true
                    break
                }
                buf.append(cbuf, 0, read)
            }
            LimitedRead(buf.toString(), if (truncated) "Datei wurde auf $MAX_INGEST_CHARS Zeichen gekappt." else null)
        } ?: LimitedRead(null, "InputStream konnte nicht geoeffnet werden.")
    } catch (e: Exception) {
        LimitedRead(null, "Lesen fehlgeschlagen: ${e.message}")
    }
}

private data class LimitedRead(val text: String?, val warning: String?)

// ─────────────────────────── UI ───────────────────────────

@Composable
private fun LoadingView() {
    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FailureView(state: ShareLoadState.Failed) {
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text("Empfang fehlgeschlagen") })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Der geteilte Inhalt konnte nicht verarbeitet werden. Der Fehler wurde in der Inbox protokolliert.",
                style = MaterialTheme.typography.bodyMedium
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(state.message, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.stack,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!state.persisted) {
                Text(
                    "Achtung: Auch die Fehlerprotokollierung schlug fehl.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Button(onClick = { (context as? android.app.Activity)?.finishAffinity() }) {
                Text("Schliessen")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareEditor(s: ShareLoadState.Ready) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = remember {
        CompanionDatabase.get(context.applicationContext).ingestItemDao()
    }

    var title by remember { mutableStateOf(suggestTitle(s.preview)) }
    var templateId by remember {
        mutableStateOf(com.heddrich.companion.llm.Templates.defaultFor(s.sourceKind.name))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Neuer Eintrag") },
                actions = {
                    IconButton(onClick = { (context as? android.app.Activity)?.finishAffinity() }) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(sourceLabel(s.sourceKind)) })
                if (!s.warning.isNullOrBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Hinweis", color = MaterialTheme.colorScheme.error) }
                    )
                }
            }
            if (!s.warning.isNullOrBlank()) {
                Text(s.warning, style = MaterialTheme.typography.labelSmall)
            }
            Text("Vorschau", style = MaterialTheme.typography.labelLarge)
            Text(
                s.preview.take(400).ifBlank { "(kein Text empfangen)" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Titel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Vorlagenwahl (Defaults + Wiki-Overrides aus dem TemplateCache)
            Text("Vorlage", style = MaterialTheme.typography.labelLarge)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                com.heddrich.companion.llm.TemplateCache.templates.forEach { t ->
                    FilterChip(
                        selected = templateId == t.id,
                        onClick = { templateId = t.id },
                        label = {
                            Text(
                                t.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val id = withContext(Dispatchers.IO) {
                                val existing = dao.getById(s.itemId)
                                existing?.let {
                                    dao.update(it.copy(title = title, templateId = templateId))
                                }
                                s.itemId
                            }
                            // Auto-Start: Nach dem Speichern sofort verarbeiten,
                            // kein manuelles Antippen in der Inbox noetig.
                            com.heddrich.companion.publish.SummarizeWorker.enqueue(
                                context.applicationContext, id
                            )
                            (context as? android.app.Activity)?.finishAffinity()
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text("Speichern")
                }
                OutlinedButton(onClick = { (context as? android.app.Activity)?.finishAffinity() }) {
                    Text("Spaeter")
                }
            }
            Text(
                if (com.heddrich.companion.settings.SettingsStore.Holder
                        .get(context.applicationContext).isLlmConfigured
                )
                    "Wird mit KI zusammengefasst und nach BookStack publiziert."
                else
                    "Keine KI konfiguriert – Rohtext wird direkt nach BookStack publiziert.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

fun suggestTitle(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        firstLine.isEmpty() -> "Eintrag ${
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        }"
        firstLine.length <= 60 -> firstLine
        else -> firstLine.take(57) + "..."
    }
}

fun sourceLabel(kind: SourceKind): String = when (kind) {
    SourceKind.SHERPA -> "Quelle: Sherpa Transcript"
    SourceKind.BROWSER -> "Quelle: Browser"
    SourceKind.WHATSAPP -> "Quelle: WhatsApp"
    SourceKind.EMAIL -> "Quelle: E-Mail"
    SourceKind.FILES -> "Quelle: Dateien"
    SourceKind.OTHER_APP -> "Quelle: andere App"
    SourceKind.UNKNOWN -> "Quelle: unbekannt"
}
