package com.heddrich.companion.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heddrich.companion.CrashGuard
import com.heddrich.companion.share.ShareSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Info-Tab mit Versionszeile, Diagnose-Sektion (Crash-Bericht + Empfangs-
 * Selbsttest) – Feldbefund-Workflow 0.2.x: Befunde auf dem Geraet sichtbar
 * machen statt blind zu raten.
 */
@Composable
fun InfoSection(versionLine: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var crashReport by remember { mutableStateOf<String?>(null) }
    var selfTestResult by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            crashReport = CrashGuard.crashFile(context.applicationContext)
                .takeIf { it.exists() }?.readText()
        }
    }

    // Lokale Kopie fuer Smart-Cast (delegierte Properties kann man nicht casten)
    val currentCrash = crashReport

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MirMirStack", style = MaterialTheme.typography.headlineMedium)
        Text("Share Target -> BookStack", style = MaterialTheme.typography.bodyLarge)
        Text(versionLine, style = MaterialTheme.typography.labelMedium)

        HorizontalDivider()

        Text("Diagnose", style = MaterialTheme.typography.titleMedium)

        if (currentCrash != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Letzter Crash",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        currentCrash,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", currentCrash))
                    copied = true
                }) { Text(if (copied) "Kopiert" else "In Zwischenablage") }
            }
        } else {
            Text(
                "Kein Crash aufgezeichnet.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    selfTestResult = withContext(Dispatchers.IO) {
                        ShareSelfTest.run(context.applicationContext)
                    }
                }
            }) { Text("Empfangs-Selbsttest") }
        }
        selfTestResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4,
                 overflow = TextOverflow.Ellipsis)
        }
    }
}
