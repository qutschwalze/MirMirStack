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
import com.heddrich.companion.bookstack.TagDto
import com.heddrich.companion.data.CompanionDatabase
import com.heddrich.companion.data.IngestStatus
import com.heddrich.companion.llm.LlmClient
import com.heddrich.companion.llm.MdRenderer
import com.heddrich.companion.llm.SummaryParser
import com.heddrich.companion.llm.SummaryResult
import com.heddrich.companion.llm.TemplateCache
import com.heddrich.companion.llm.Templates
import com.heddrich.companion.settings.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Phase-3-Worker: PROCESS (LLM-Zusammenfassung) -> PUBLISH (BookStack).
 *
 * Ohne LLM-Konfiguration faellt er deterministisch auf das Phase-2-Verhalten
 * zurueck (Rohtext-Seite), damit die Pipeline nie stillsteht.
 * Bei erfolgreichem LLM-Lauf ersetzt der LLM-Titel den Auto-Titel.
 */
class SummarizeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = CompanionDatabase.get(applicationContext).ingestItemDao()
        val id = inputData.getLong(PublishWorker.KEY_ITEM_ID, -1L)
        if (id <= 0L) return Result.failure()

        var item = dao.getById(id) ?: return Result.failure()
        if (item.status == IngestStatus.DONE) return Result.success()

        dao.update(item.copy(status = IngestStatus.RUNNING))

        // ── PROCESS ────────────────────────────────────────────────────────
        val processed = try {
            process(item)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // Abbruch (Prozess-Stop/Netzwechsel): SOFORT weiterreichen, KEINE
            // Suspend-Aufrufe mehr hier (in einer abgebrochenen Coroutine
            // wuerden sie sofort wieder abbrechen – genau das verursachte den
            // „Unterbrochen“-Limbo). Der Status bleibt RUNNING; der Start-Sweep
            // (App.onCreate) nimmt solche Items mit und startet sie neu.
            throw ce
        } catch (e: Exception) {
            // Verstaendliche Diagnose: HTTP-Code + Body-Auszug statt nur Klassenname
            val reason = when (e) {
                is retrofit2.HttpException -> {
                    val body = try {
                        e.response()?.errorBody()?.string().orEmpty().take(200)
                    } catch (_: Exception) { "" }
                    "LLM HTTP ${e.code()}: ${body.ifBlank { e.message().orEmpty().take(120) }}"
                }
                else -> "LLM: ${e.message?.take(200) ?: e.javaClass.simpleName}"
            }
            dao.update(
                item.copy(status = IngestStatus.FAILED, error = reason)
            )
            return if (runAttemptCount < MAX_ATTEMPTS && isTransient(e)) Result.retry() else Result.failure()
        }

        if (processed is Processed.Summary) {
            // LLM-Titel uebernimmt den Auto-Titel (fixt die "mitten im Satz"-Titel)
            item = item.copy(title = processed.summary.title, summaryMd = processed.summary.summaryMd)
            dao.update(item)
        }
        val html = when (processed) {
            is Processed.Summary -> renderSummary(processed.summary)
            is Processed.Fallback -> processed.html
        }
        val tags = when (processed) {
            is Processed.Summary ->
                buildTags(item, processed.template, processed.summary)
            is Processed.Fallback -> emptyList()
        }

        // ── PUBLISH ────────────────────────────────────────────────────────
        return when (val result = Publisher.publishHtml(applicationContext, item, html, tags)) {
            is PublishResult.Success -> {
                dao.update(item.copy(status = IngestStatus.DONE, resultUrl = result.wikiUrl, error = null))
                Result.success()
            }
            is PublishResult.Failure -> {
                dao.update(item.copy(status = IngestStatus.FAILED, error = result.reason))
                if (runAttemptCount < MAX_ATTEMPTS && result.reason.contains("nicht erreichbar")) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }

    /** Zusammenfassung via LLM – oder Fallback Rohtext, wenn LLM nicht konfiguriert ist. */
    private suspend fun process(item: com.heddrich.companion.data.IngestItem): Processed {
        val settings = SettingsStore.Holder.get(applicationContext)
        val rawText = item.rawText.orEmpty()
        if (!settings.isLlmConfigured || rawText.isBlank()) {
            return Processed.Fallback(fallbackHtml(rawText))
        }
        // Wiki-Overrides frisch ziehen (fehler-tolerant, Cache bleibt bei Problemen)
        TemplateCache.refresh(applicationContext)
        val template = TemplateCache.find(item.templateId)

        val client = LlmClient(settings.llmBaseUrl, settings.llmApiKey)
        val answer = client.complete(settings.llmModel, template.systemPrompt, rawText)
        return Processed.Summary(SummaryParser.parse(answer), template)
    }

    private sealed interface Processed {
        data class Summary(
            val summary: SummaryResult,
            val template: com.heddrich.companion.llm.Template
        ) : Processed
        data class Fallback(val html: String) : Processed
    }

    /**
     * Effektive Wiki-Tags: Vorlagen-Defaults (z. B. typ=meeting), Quelle,
     * thematische Tags aus dem LLM (thema=…) und Personen (person=…).
     */
    private fun buildTags(
        item: com.heddrich.companion.data.IngestItem,
        template: com.heddrich.companion.llm.Template,
        summary: SummaryResult
    ): List<TagDto> {
        val tags = mutableListOf<TagDto>()
        fun add(name: String, value: String) {
            val v = value.trim()
            if (v.isNotEmpty() && tags.none { it.name == name && it.value.equals(v, true) }) {
                tags.add(TagDto(name, v))
            }
        }
        for (t in template.defaultTags) {
            val i = t.indexOf('=')
            if (i > 0) add(t.substring(0, i).trim(), t.substring(i + 1))
        }
        add("quelle", item.sourceKind.name.lowercase())
        summary.tags.forEach { add("thema", it) }
        summary.participants.forEach { add("person", it) }
        return tags
    }

    private fun fallbackHtml(rawText: String): String {
        val esc = MdRenderer.escapeHtml(rawText.take(50_000)).replace("\n", "<br>")
        return "<p>$esc</p>"
    }

    private fun renderSummary(s: SummaryResult): String = buildString {
        append("<div>")
        append(MdRenderer.render(s.summaryMd))
        if (s.decisions.isNotEmpty()) {
            append("<h3>Entscheidungen</h3><ul>")
            s.decisions.forEach { append("<li>").append(MdRenderer.escapeHtml(it)).append("</li>") }
            append("</ul>")
        }
        if (s.todos.isNotEmpty()) {
            append("<h3>To-dos</h3><ul>")
            s.todos.forEach { append("<li>").append(MdRenderer.escapeHtml(it)).append("</li>") }
            append("</ul>")
        }
        if (s.participants.isNotEmpty()) {
            append("<p><em>Teilnehmer: ")
                .append(MdRenderer.escapeHtml(s.participants.joinToString(", ")))
                .append("</em></p>")
        }
        append("</div>")
    }

    private fun isTransient(e: Exception): Boolean {
        if (e is retrofit2.HttpException) {
            return e.code() == 429 || e.code() in 500..599
        }
        val m = e.message.orEmpty().lowercase()
        return m.contains("timeout") || m.contains("429") || m.contains("rate") ||
                m.contains("unable to resolve host") || e is java.io.IOException
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /**
         * force=false: Laufender Job bleibt unberuehrt (KEEP) – fuer Auto-Start.
         * force=true: Bricht einen haengenden/stehengebliebenen Eintrag ab und
         * startet neu (REPLACE) – fuer manuelles Antippen und Start-Sweep.
         */
        fun cancel(context: Context, itemId: Long) {
            androidx.work.WorkManager.getInstance(context)
                .cancelUniqueWork("summarize-$itemId")
        }

        fun enqueue(context: Context, itemId: Long, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SummarizeWorker>()
                .setInputData(workDataOf(PublishWorker.KEY_ITEM_ID to itemId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                // Expedited = sofortige Ausfuehrung statt Warteschlangen-Latenz
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "summarize-$itemId",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Smart-Tap fuer die Inbox: Läuft ein Job wirklich (ENQUEUED/RUNNING im
         * WorkManager), wird er nicht angetastet — das fruehere blinde REPLACE
         * hat einen aktiven Lauf gekillt und damit die „Unterbrochen“-Schleife
         * verursacht. Nur wenn NICHTS aktiv ist (verwaister Queue-Eintrag oder
         * FINISHED/CANCELLED), wird mit REPLACE neu gestartet.
         * Liefert eine kurze Rueckmeldung fuer UI/Snackbar.
         */
        suspend fun tapResume(context: Context, itemId: Long): String {
            val wm = androidx.work.WorkManager.getInstance(context)
            val info = wm.getWorkInfosForUniqueWork("summarize-$itemId").get().firstOrNull()
            return if (info != null &&
                (info.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        info.state == androidx.work.WorkInfo.State.RUNNING)
            ) {
                "Job läuft bereits – kein Neustart nötig"
            } else {
                enqueue(context, itemId, force = true)
                "Neustart angestoßen"
            }
        }
    }
}
