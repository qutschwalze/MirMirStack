package com.heddrich.companion

import android.app.Application
import com.heddrich.companion.data.CompanionDatabase
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
     * Recovery-Sweep beim App-Start: Nach einem Force-Stop haelt Android
     * WorkManager-Jobs an bis die App wieder geoeffnet wird. Alle Items,
     * die gespeichert (templateId gesetzt), aber nie verarbeitet wurden,
     * werden hier neu eingereiht – force ueberschreibt verwaiste Eintraege.
     */
    private fun recoverStalledQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CompanionDatabase.get(this@App).ingestItemDao()
                    .queuedSubmitted()
                    .forEach { SummarizeWorker.enqueue(this@App, it.id, force = true) }
            } catch (_: Throwable) {
                // Recovery darf den App-Start niemals brechen
            }
        }
    }
}
