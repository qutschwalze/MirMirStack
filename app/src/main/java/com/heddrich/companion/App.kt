package com.heddrich.companion

import android.app.Application
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.publish.SummarizeWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CompanionDatabase.appContext = this
        CrashGuard.install(this)
        PDFBoxResourceLoader.init(applicationContext)
        recoverStalledQueue()
    }

    /**
     * Recovery-Sweep beim App-Start: Nach einem Prozess-Stop haelt Android
     * WorkManager-Jobs an bis die App wieder geoeffnet wird. Alle Items, die
     * eingereicht aber nie fertig wurden (QUEUD **und** RUNNING-Limbo aus
     * abgebrochenen Laeufen), werden hier sauber auf QUEUED gesetzt und
     * force-neu eingereiht (REPLACE ueberschreibt verwaiste Queue-Eintraege).
     */
    private fun recoverStalledQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = CompanionDatabase.get(this@App).ingestItemDao()
                dao.submittedNotDone().forEach { item ->
                    dao.update(
                        item.copy(
                            status = IngestStatus.QUEUED,
                            error = null // altes „Unterbrochen…“ aufräumen
                        )
                    )
                    SummarizeWorker.enqueue(this@App, item.id, force = true)
                }
            } catch (_: Throwable) {
                // Recovery darf den App-Start niemals brechen
            }
        }
    }
}
