package ru.neurosteno.app

/**
 * Точка подключения локальной LLM. Пока engine-llm (llama.cpp) не собран, фабрика возвращает null,
 * и конвейер оставляет текст ASR (GigaAM v3 e2e уже даёт пунктуацию и регистр).
 */
object LlmProvider {
    @Volatile
    var factory: LlmFactory = LlmFactory { null }
}
