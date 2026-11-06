package ru.neurosteno.llm

import ru.neurosteno.core.LlmBackend
import java.io.File

/** Колбэк отмены, вызывается из нативного кода между токенами (тот же поток, что complete). */
fun interface Stopper { fun shouldStop(): Boolean }

internal object LlamaNative {
    init { System.loadLibrary("neurollm") }
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    external fun complete(
        handle: Long, system: ByteArray, user: ByteArray, maxTokens: Int, singleLine: Boolean, stopper: Stopper,
    ): ByteArray?
    external fun free(handle: Long)
}

/** llama.cpp + GGUF-модель. Greedy-декодирование: детерминированный ответ. Не потокобезопасен. */
class LlamaBackend(modelPath: String, nThreads: Int, nCtx: Int = 2048) : LlmBackend {
    private var handle: Long = LlamaNative.load(modelPath, nCtx, nThreads)

    init { check(handle != 0L) { "Не удалось загрузить LLM: $modelPath" } }

    override fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean): String =
        run(system, user, maxTokens, true, shouldStop)

    override fun completeMultiline(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean): String =
        run(system, user, maxTokens, false, shouldStop)

    private fun run(system: String, user: String, maxTokens: Int, singleLine: Boolean, shouldStop: () -> Boolean): String {
        check(handle != 0L) { "LLM закрыта" }
        val bytes = LlamaNative.complete(handle, system.toByteArray(), user.toByteArray(), maxTokens, singleLine, Stopper(shouldStop))
            ?: return ""
        return String(bytes, Charsets.UTF_8)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) { LlamaNative.free(handle); handle = 0L }
    }

    companion object {
        /**
         * libneurollm.so собрана под armv8.2-a+dotprod+fp16 (быстрый Q4_K). На armv8.0 (Snapdragon 4xx/6xx,
         * Helio P22/G35) это SIGILL — поэтому перед загрузкой проверяем флаги CPU; без них LLM отключается.
         * На не-arm64 (эмулятор x86_64) библиотека собрана без этих расширений — проверка не нужна.
         */
        fun cpuSupported(): Boolean {
            val abi = System.getProperty("os.arch").orEmpty()
            if (!abi.contains("aarch64") && !abi.contains("arm64")) return true
            val features = runCatching {
                File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }.orEmpty()
            }.getOrDefault("").split(Regex("\\s+")).toSet()
            return "asimddp" in features && ("fphp" in features || "asimdhp" in features)
        }
    }
}
