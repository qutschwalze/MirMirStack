package com.heddrich.companion

import android.app.Application
import android.content.Context
import android.util.Log
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestItem
import com.heddrich.companion.data.IngestStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Globaler Uncaught-Exception-Handler (v0.2.2).
 *
 * Feldbefund 0.2.0/0.2.1: Beim Teilen schliesst sich die App sofort – der
 * Crash liegt offenbar AUSSERHALB des abgesicherten Empfangspfads (Composition
 * oder Activity-Start). Dieser Guard faengt JEDE unbehandelte Ausnahme,
 * schreibt sie in eine Datei UND (best effort) als FAILED-Eintrag in die
 * Outbox, sodass der Befund den Prozesscrash ueberlebt und in der App
 * abrufbar bleibt. Danach wird der normale Crash-Pfad fortgesetzt.
 */
object CrashGuard {

    const val CRASH_FILE_NAME = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildReport(thread, throwable)
            try {
                crashFile(app).writeText(report)
            } catch (_: Throwable) {
            }
            try {
                // Best effort: Befund in die Outbox (ueberlebt den Crash, da Room
                // synchron in SQLite schreibt). Eigenes Catch, niemals rethrowen.
                runBlocking {
                    CompanionDatabase.get(app).ingestItemDao().insert(
                        IngestItem(
                            createdAt = System.currentTimeMillis(),
                            sourcePkg = "crash",
                            sourceKind = com.heddrich.companion.share.SourceKind.UNKNOWN,
                            templateId = null,
                            title = "CRASH ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}",
                            rawText = null,
                            rawUri = null,
                            mime = null,
                            status = IngestStatus.FAILED,
                            error = report.take(1500),
                            resultUrl = null
                        )
                    )
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashFile(app: Context): File =
        File(File(app.filesDir, "crash").apply { mkdirs() }, CRASH_FILE_NAME)

    private fun buildReport(thread: Thread, t: Throwable): String = buildString {
        appendLine("Time : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine("Thread: ${thread.name}")
        appendLine("Exception: ${t.javaClass.name}")
        appendLine("Message: ${t.message ?: "(none)"}")
        appendLine()
        appendLine(Log.getStackTraceString(t))
        // Cause-Kette komplett (Compose-Wraps verschleiern sonst die Ursache)
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 5) {
            appendLine()
            appendLine("CAUSED BY ($depth): ${cause.javaClass.name}: ${cause.message}")
            appendLine(Log.getStackTraceString(cause))
            cause = cause.cause
            depth++
        }
    }.take(8000)
}
