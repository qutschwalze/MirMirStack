package com.heddrich.companion.publish

import android.content.Context
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.settings.SettingsStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Prueft kurz nach dem 202-Acknowledgement, ob der Server die
 * Verarbeitung tatsaechlich erfolgreich durchgefuehrt hat (LLM +
 * Seitenanlage). Liegt ein Fehler vor, wird der Eintrag in der Inbox
 * auf FAILED mit der Server-Meldung gesetzt.
 *
 * Grund: Der Server antwortet sofort mit 202 (asynchron); LLM-Fehler
 * bleiben sonst unsichtbar. Nur noetig im Server-Modus.
 */
class VerifyWorker(appContext: Context, params: WorkerParameters)
    : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ITEM_ID, -1L)
        if (id <= 0L) return Result.failure()

        val settings = SettingsStore.Holder.get(applicationContext)
        if (!settings.isServerMode || !settings.isIngestConfigured) return Result.success()

        // 1) Status-Endpoint abfragen
        val latest = ServerPublisher.verifyStatus(applicationContext)
        if (latest == null) return Result.success() // Netzwerk-Fehler: still lassen, nicht als Fehler melden

        // 2) Ist der letzte Status ein Fehler?
        if (latest.first == "error") {
            val dao = CompanionDatabase.get(applicationContext).ingestItemDao()
            val item = dao.getById(id) ?: return Result.success()
            // Nur korrigieren, wenn der Eintrag noch als DONE markiert ist
            // (normale Verarbeitung war erfolgreich, LLM aber nicht)
            if (item.status == IngestStatus.DONE && item.resultUrl == null) {
                dao.update(
                    item.copy(
                        status = IngestStatus.FAILED,
                        error = "Server-Fehler nach Uebergabe: ${latest.second.take(200)}"
                    )
                )
                com.heddrich.companion.notify.AppNotifier.serverError(
                    applicationContext, item.title, latest.second
                )
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val DELAY_SECONDS = 30L

        fun enqueue(context: Context, itemId: Long) {
            val request = OneTimeWorkRequestBuilder<VerifyWorker>()
                .setInputData(workDataOf(KEY_ITEM_ID to itemId))
                .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "verify-$itemId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
