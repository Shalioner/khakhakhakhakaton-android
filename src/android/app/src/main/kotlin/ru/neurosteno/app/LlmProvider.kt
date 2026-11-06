package ru.neurosteno.app

import android.util.Log
import ru.neurosteno.llm.LlamaBackend
import ru.neurosteno.llm.ModelFiles

/**
 * Локальная LLM (llama.cpp + Qwen2.5-0.5B Q4_K_M). Если модели нет или загрузка упала —
 * фабрика возвращает null, и конвейер оставляет текст ASR (правило fallback из AGENTS.md).
 */
object LlmProvider {
    @Volatile
    var factory: LlmFactory = LlmFactory { ctx ->
        runCatching {
            if (!LlamaBackend.cpuSupported()) {
                Log.w(Pipeline.TAG, "CPU без dotprod/fp16 — LLM отключена")
                return@runCatching null
            }
            val f = ModelFiles.ensureOnDisk(ctx) ?: return@runCatching null
            LlamaBackend(f.path, nThreads = Pipeline.defaultThreads().coerceAtMost(4))
        }.onFailure { Log.e(Pipeline.TAG, "LLM init failed", it) }.getOrNull()
    }
}
