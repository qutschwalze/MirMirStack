package com.heddrich.companion.inbox

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.publish.SummarizeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inbox-ViewModel: beobachtet die Outbox-Tabelle als Flow.
 */
class InboxViewModel : ViewModel() {
    private val dao = CompanionDatabase.getForVm().ingestItemDao()
    val items: Flow<List<IngestItem>> = dao.observeAll()
}

@Composable
fun InboxRoute(vm: InboxViewModel = viewModel()) {
    val items by vm.items.collectAsState(initial = emptyList())
    InboxScreen(items)
}

/**
 * Inbox mit Mehrfachauswahl:
 * - Karte lange druecken -> Auswahlmodus (weitere Karten antippen = markieren)
 * - Papierkorb oben loescht alle markierten Eintraege (Worker werden gestoppt)
 * - Normaler Tap: QUEUED/FAILED verarbeiten/wiederholen; DONE oeffnet die Wiki-Seite
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(items: List<IngestItem>) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val selected = remember { mutableStateListOf<Long>() }
    val dao = remember { CompanionDatabase.get(context.applicationContext).ingestItemDao() }

    fun deleteSelected() {
        if (selected.isEmpty()) return
        val ids = selected.toList()
        ids.forEach { SummarizeWorker.cancel(context, it) }
        selected.clear()
        scope.launch(Dispatchers.IO) { dao.deleteAll(ids) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Inbox" else "${selected.size} ausgewählt") },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { deleteSelected() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Auswahl löschen")
                        }
                        IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Auswahl beenden")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Noch keine Einträge.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Teile Text oder eine Datei (md/txt/json/pdf), " +
                            "oder markiere Text und nutze das Auswahlmenü.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val isSelected = selected.contains(item.id)
                    IngestRow(
                        item = item,
                        isSelected = isSelected,
                        onClick = {
                            when {
                                // Auswahlmodus aktiv: Tap markiert/entmarkiert
                                selected.isNotEmpty() -> {
                                    if (isSelected) selected.remove(item.id)
                                    else selected.add(item.id)
                                }
                                item.status == IngestStatus.DONE &&
                                        !item.resultUrl.isNullOrBlank() -> {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(item.resultUrl))
                                    )
                                }
                                item.status == IngestStatus.QUEUED ||
                                        item.status == IngestStatus.FAILED -> {
                                    SummarizeWorker.enqueue(context, item.id, force = true)
                                }
                            }
                        },
                        onLongClick = {
                            if (isSelected) selected.remove(item.id) else selected.add(item.id)
                        },
                        onDelete = {
                            SummarizeWorker.cancel(context, item.id)
                            scope.launch(Dispatchers.IO) { dao.delete(item.id) }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IngestRow(
    item: IngestItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (isSelected) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    (if (isSelected) "✓ " else "") + (item.title ?: "(ohne Titel)"),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                AssistChip(onClick = {}, label = { Text(statusLabel(item.status)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceLabelShort(item.sourceKind.name), style = MaterialTheme.typography.labelSmall)
                Text(dateFormat(item.createdAt), style = MaterialTheme.typography.labelSmall)
                if (item.rawText != null || item.rawLocalPath != null) {
                    Text(sizeLabel(item), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!item.error.isNullOrBlank()) {
                Text(
                    item.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (item.status) {
                    IngestStatus.DONE -> if (!item.resultUrl.isNullOrBlank()) {
                        Button(onClick = {
                            // onClick auf der Karte oeffnet bereits; Button fuer Klarheit
                        }) { Text("Wiki-Seite öffnen") }
                    }
                    IngestStatus.RUNNING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    IngestStatus.FAILED -> Text(
                        "Zum Wiederholen antippen",
                        style = MaterialTheme.typography.labelSmall
                    )
                    IngestStatus.QUEUED -> if (!item.templateId.isNullOrEmpty()) Text(
                        "Warten auf Verarbeitung – antippen startet",
                        style = MaterialTheme.typography.labelSmall
                    )
                    else -> {}
                }
            }
        }
    }
}

private fun statusLabel(s: IngestStatus): String = when (s) {
    IngestStatus.QUEUED -> "QUEUED"
    IngestStatus.RUNNING -> "LÄUFT"
    IngestStatus.DONE -> "FERTIG"
    IngestStatus.FAILED -> "FEHLER"
}

private fun sourceLabelShort(kindName: String): String = when (kindName) {
    "SHERPA" -> "Sherpa"
    "BROWSER" -> "Browser"
    "WHATSAPP" -> "WhatsApp"
    "EMAIL" -> "Mail"
    "FILES" -> "Dateien"
    "OTHER_APP" -> "App"
    else -> "Unbekannt"
}

private fun sizeLabel(item: IngestItem): String {
    val chars = item.rawText?.length ?: 0
    return if (item.rawLocalPath != null) "PDF ($chars Z. Text)" else "$chars Zeichen"
}

private fun dateFormat(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
