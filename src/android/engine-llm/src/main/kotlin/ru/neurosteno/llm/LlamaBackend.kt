package ru.neurosteno.llm

import ru.neurosteno.core.LlmBackend

/** Колбэк отмены, вызывается из нативного кода между токенами. */
fun interface Stopper { fun shouldStop(): Boolean }

internal object LlamaNative {
    init { System.loadLibrary("neurollm") }
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    external fun complete(handle: Long, system: ByteArray, user: ByteArray, maxTokens: Int, stopper: Stopper): ByteArray?
    external fun free(handle: Long)
}

/** llama.cpp + GGUF-модель. Greedy-декодирование: детерминированный ответ. Не потокобезопасен. */
class LlamaBackend(modelPath: String, nThreads: Int, nCtx: Int = 2048) : LlmBackend {
    private var handle: Long = LlamaNative.load(modelPath, nCtx, nThreads)

    init { check(handle != 0L) { "Не удалось загрузить LLM: $modelPath" } }

    override fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean): String {
        check(handle != 0L) { "LLM закрыта" }
        val bytes = LlamaNative.complete(handle, system.toByteArray(), user.toByteArray(), maxTokens, Stopper(shouldStop))
            ?: return ""
        return String(bytes, Charsets.UTF_8)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) { LlamaNative.free(handle); handle = 0L }
    }
}
