package ru.neurosteno.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.neurosteno.core.JsonExporter
import java.io.File

/**
 * Прогон набора файлов на телефоне: adb shell am start -n ru.neurosteno.app/.BenchActivity --es dir <path>
 * По умолчанию dir = getExternalFilesDir("bench") (/sdcard/Android/data/ru.neurosteno.app/files/bench — туда можно adb push без разрешений).
 * Пишет <name>.json и timings.json в <dir>/out.
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(tv)
        val dir = intent.getStringExtra("dir")?.let(::File) ?: getExternalFilesDir("bench")!!
        val out = File(dir, "out").apply { mkdirs() }
        val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXT }?.sorted().orEmpty()
        tv.text = "Bench: ${files.size} файлов в $dir"
        // Параметры подбора без пересборки: --ei threads N --ef shift 0.2 --ez seq true
        val threads = intent.getIntExtra("threads", Pipeline.defaultThreads())
        val benchEngine = ru.neurosteno.sherpa.EngineConfig(
            numThreads = threads,
            segWindowShiftRatio = intent.getFloatExtra("shift", 0.1f),
        )
        val benchCfg = ru.neurosteno.core.PipelineConfig().let {
            if (intent.getBooleanExtra("seq", false)) it.copy(parallelMaxMs = 0) else it
        }
        Log.i(Pipeline.TAG, "bench config: threads=$threads shift=${benchEngine.segWindowShiftRatio} seq=${benchCfg.parallelMaxMs == 0L}")
        CoroutineScope(Dispatchers.Default).launch {
            val timings = StringBuilder("{\n")
            files.forEachIndexed { i, f ->
                val line = runCatching {
                    val r = Pipeline(applicationContext, cfg = benchCfg, engineCfg = benchEngine, llmFactory = LlmProvider.factory).run(Uri.fromFile(f), { _, _ -> }) { false }
                    File(out, f.nameWithoutExtension + ".json").writeText(JsonExporter.toJson(r.transcript))
                    val mem = Debug.getNativeHeapAllocatedSize() / (1 shl 20)
                    val stages = r.timings.joinToString(",") { "\"${it.stage.name.lowercase()}_ms\":${it.ms}" }
                    "\"${f.nameWithoutExtension}\":{\"audio_ms\":${r.transcript.durationMs},\"total_ms\":${r.totalMs},\"proc_ms\":${r.totalMs},\"rtf\":${"%.4f".format(java.util.Locale.US, r.rtf)},\"native_heap_mb\":$mem,$stages}"
                }.getOrElse { "\"${f.nameWithoutExtension}\":{\"error\":${JsonExporter.quote(it.toString())}}" }
                Log.i(Pipeline.TAG, "bench $line")
                timings.append("  ").append(line).append(if (i < files.size - 1) ",\n" else "\n")
                withContext(Dispatchers.Main) { tv.text = "Bench: ${i + 1}/${files.size}\n$line" }
            }
            timings.append("}\n")
            File(out, "timings.json").writeText(timings.toString())
            withContext(Dispatchers.Main) { tv.append("\n\nГотово: ${out.absolutePath}") }
            Log.i(Pipeline.TAG, "bench done: ${out.absolutePath}")
        }
    }

    companion object {
        val AUDIO_EXT = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr", "3gp", "mp4", "webm")
    }
}
