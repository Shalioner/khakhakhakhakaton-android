package ru.neurosteno.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.Utterance
import java.io.File

/** Запись в истории: метаданные + расшифровка (в файле — строго JSON-контракт). */
data class HistoryEntry(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val speakers: Int,
    val utterances: Int,
    val processingMs: Long,
    val audioUri: String?,
    val warnings: List<String>,
)

/**
 * История расшифровок во внутреннем хранилище (filesDir/history): <id>.json — экспорт по контракту,
 * <id>.meta.json — метаданные для списка. Без сети, без БД.
 */
object HistoryStore {

    private fun dir(ctx: Context) = File(ctx.filesDir, "history").apply { mkdirs() }

    fun add(ctx: Context, name: String, audioUri: String?, r: PipelineResult): HistoryEntry {
        val id = System.currentTimeMillis().toString()
        val e = HistoryEntry(
            id = id,
            name = name.substringBeforeLast('.').ifBlank { name },
            createdAt = System.currentTimeMillis(),
            durationMs = r.transcript.durationMs,
            speakers = r.transcript.utterances.map { it.speakerId }.distinct().size,
            utterances = r.transcript.utterances.size,
            processingMs = r.totalMs,
            audioUri = audioUri,
            warnings = r.warnings,
        )
        File(dir(ctx), "$id.json").writeText(JsonExporter.toJson(r.transcript))
        File(dir(ctx), "$id.meta.json").writeText(
            JSONObject()
                .put("name", e.name).put("createdAt", e.createdAt).put("durationMs", e.durationMs)
                .put("speakers", e.speakers).put("utterances", e.utterances).put("processingMs", e.processingMs)
                .put("audioUri", e.audioUri ?: JSONObject.NULL)
                .put("warnings", org.json.JSONArray(e.warnings))
                .toString(),
        )
        return e
    }

    fun list(ctx: Context): List<HistoryEntry> =
        dir(ctx).listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .mapNotNull { f -> runCatching { readMeta(f.name.removeSuffix(".meta.json"), f) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun get(ctx: Context, id: String): HistoryEntry? =
        File(dir(ctx), "$id.meta.json").takeIf { it.exists() }?.let { runCatching { readMeta(id, it) }.getOrNull() }

    private fun readMeta(id: String, f: File): HistoryEntry {
        val o = JSONObject(f.readText())
        val w = o.optJSONArray("warnings")
        return HistoryEntry(
            id = id,
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            durationMs = o.getLong("durationMs"),
            speakers = o.optInt("speakers"),
            utterances = o.optInt("utterances"),
            processingMs = o.optLong("processingMs"),
            audioUri = if (o.isNull("audioUri")) null else o.optString("audioUri"),
            warnings = List(w?.length() ?: 0) { w!!.getString(it) },
        )
    }

    /** Расшифровка из файла контракта. */
    fun transcript(ctx: Context, e: HistoryEntry): Transcript? = runCatching {
        val arr = JSONObject(File(dir(ctx), "${e.id}.json").readText()).getJSONArray("transcript")
        Transcript(
            List(arr.length()) { i ->
                val u = arr.getJSONObject(i)
                Utterance(u.getString("speaker_id"), u.getLong("start_ms"), u.getLong("end_ms"), u.getString("text"))
            },
            e.durationMs,
        )
    }.onFailure { Log.e(Pipeline.TAG, "history read failed", it) }.getOrNull()

    fun delete(ctx: Context, e: HistoryEntry) {
        File(dir(ctx), "${e.id}.json").delete()
        File(dir(ctx), "${e.id}.meta.json").delete()
        // Запись с микрофона хранится у нас — удаляем вместе с расшифровкой. Чужие файлы (SAF) не трогаем.
        e.audioUri?.takeIf { it.startsWith("file:") }?.let { u ->
            val f = File(android.net.Uri.parse(u).path ?: return@let)
            if (f.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) f.delete()
        }
    }
}
