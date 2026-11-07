package ru.neurosteno.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.Utterance
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    val speakerNames: Map<String, String> = emptyMap(),
)

/**
 * История расшифровок во внутреннем хранилище (filesDir/history): <id>.json — экспорт по контракту,
 * <id>.meta.json — метаданные для списка. Без сети, без БД.
 */
object HistoryStore {

    private val audioExt = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr", "3gp", "mp4", "webm")

    /** Имя без расширения — только если это действительно аудиорасширение («Запись 7 нояб., 18:22» не режем). */
    fun displayTitle(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in audioExt) name.substringBeforeLast('.').ifBlank { name } else name
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "history").apply { mkdirs() }

    fun add(ctx: Context, name: String, audioUri: String?, r: PipelineResult): HistoryEntry {
        val id = System.currentTimeMillis().toString()
        val e = HistoryEntry(
            id = id,
            name = displayTitle(name),
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

    internal fun readMeta(id: String, f: File): HistoryEntry {
        val o = JSONObject(f.readText())
        val w = o.optJSONArray("warnings")
        val names = o.optJSONObject("speakerNames")
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
            speakerNames = names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
        )
    }

    /** Расшифровка из файла контракта. */
    fun transcript(ctx: Context, e: HistoryEntry): Transcript? = runCatching {
        readTranscript(File(dir(ctx), "${e.id}.json"), e.durationMs)
    }.onFailure { Log.e(Pipeline.TAG, "history read failed", it) }.getOrNull()

    internal fun readTranscript(file: File, durationMs: Long): Transcript {
        val arr = JSONObject(file.readText()).getJSONArray("transcript")
        return Transcript(
            List(arr.length()) { i ->
                val u = arr.getJSONObject(i)
                Utterance(u.getString("speaker_id"), u.getLong("start_ms"), u.getLong("end_ms"), u.getString("text"))
            },
            durationMs,
        )
    }

    fun renameSpeaker(ctx: Context, e: HistoryEntry, speakerId: String, name: String): HistoryEntry =
        renameSpeaker(File(dir(ctx), "${e.id}.meta.json"), e.id, speakerId, name)

    @Synchronized
    internal fun renameSpeaker(file: File, id: String, speakerId: String, name: String): HistoryEntry {
        require(name.isNotBlank())
        val meta = JSONObject(file.readText())
        val names = meta.optJSONObject("speakerNames") ?: JSONObject()
        names.put(speakerId, name.trim())
        replace(file, meta.put("speakerNames", names).toString())
        return readMeta(id, file)
    }

    fun editText(ctx: Context, e: HistoryEntry, index: Int, text: String): Transcript =
        editText(File(dir(ctx), "${e.id}.json"), e.durationMs, index, text)

    @Synchronized
    internal fun editText(file: File, durationMs: Long, index: Int, text: String): Transcript {
        require(text.isNotBlank())
        val transcript = readTranscript(file, durationMs)
        val utterances = transcript.utterances.toMutableList()
        utterances[index] = utterances[index].copy(text = text.trim())
        val updated = transcript.copy(utterances = utterances)
        replace(file, JsonExporter.toJson(updated))
        return updated
    }

    private fun replace(file: File, content: String) {
        val temp = File.createTempFile("edit-", ".tmp", file.parentFile)
        try {
            temp.writeText(content)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
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
