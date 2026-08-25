package com.heddrich.companion.inbox

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heddrich.companion.data.CompanionDatabase
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.publish.SummarizeWorker
import kotlinx.coroutines.flow.Flow
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(items: List<IngestItem>) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Inbox") }) }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text("Noch keine Eintraege.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Teile Text oder eine Datei (md/txt/json) aus einer anderen App mit MirMirStack.",
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
                    IngestRow(item)
                }
            }
        }
    }
}

@Composable
private fun IngestRow(item: IngestItem) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.status == IngestStatus.QUEUED || item.status == IngestStatus.FAILED) {
                SummarizeWorker.enqueue(context, item.id)
            }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    item.title ?: "(ohne Titel)",
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
                if (item.rawText != null) {
                    Text("${item.rawText.length} Zeichen", style = MaterialTheme.typography.labelSmall)
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
            when (item.status) {
                IngestStatus.DONE -> if (!item.resultUrl.isNullOrBlank()) {
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.resultUrl)))
                    }) { Text("Wiki-Seite öffnen") }
                }
                IngestStatus.RUNNING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "KI-Zusammenfassung läuft, danach Publikation ins Wiki (typisch 10–20 s)…",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                IngestStatus.FAILED -> {
                    Text(
                        "Zum Wiederholen antippen",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                else -> {}
            }
        }
    }
}

private fun statusLabel(s: IngestStatus): String = s.name

private fun sourceLabelShort(kindName: String): String = when (kindName) {
    "SHERPA" -> "Sherpa"
    "BROWSER" -> "Browser"
    "WHATSAPP" -> "WhatsApp"
    "EMAIL" -> "Mail"
    "FILES" -> "Dateien"
    "OTHER_APP" -> "App"
    else -> "Unbekannt"
}

private fun dateFormat(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
