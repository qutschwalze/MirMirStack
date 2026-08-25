package com.heddrich.companion.publish

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestStatus
import java.util.concurrent.TimeUnit

/**
 * Publish-Worker: fuehrt Publisher.publish fuer ein Outbox-Item aus und
 * pflegt den Status zurueck in die DB (RUNNING -> DONE/FAILED).
 *
 * Idempotenz: Publisher legt bei gleichem Seitennamen nur eine neue
 * Revision an – Retry kann also keine Duplikate erzeugen.
 */
class PublishWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ITEM_ID, -1L)
        if (id <= 0L) return Result.failure()

        val dao = CompanionDatabase.get(applicationContext).ingestItemDao()
        val item = dao.getById(id) ?: return Result.failure()
        if (item.status == IngestStatus.DONE) return Result.success()

        dao.update(item.copy(status = IngestStatus.RUNNING, error = null))

        return when (val result = Publisher.publish(applicationContext, item)) {
            is PublishResult.Success -> {
                dao.update(
                    item.copy(
                        status = IngestStatus.DONE,
                        resultUrl = result.wikiUrl,
                        error = null
                    )
                )
                Result.success()
            }
            is PublishResult.Failure -> {
                dao.update(item.copy(status = IngestStatus.FAILED, error = result.reason))
                // Konservativ: nur Netzwerkprobleme werden automatisch wiederholt;
                // Konfigurationsfehler (Token/URL) bleiben FAILED bis der Nutzer eingreift.
                if (runAttemptCount < MAX_ATTEMPTS && result.reason.contains("nicht erreichbar")) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val MAX_ATTEMPTS = 3

        /**
         * Eindeutiger Workname pro Item: REPLACE stellt sicher, dass erneutes
         * Antippen einen frischen Lauf startet statt in der Queue zu stauen.
         */
        fun enqueue(context: Context, itemId: Long) {
            val request = OneTimeWorkRequestBuilder<PublishWorker>()
                .setInputData(workDataOf(KEY_ITEM_ID to itemId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "publish-$itemId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
