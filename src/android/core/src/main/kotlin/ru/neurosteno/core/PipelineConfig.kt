package ru.neurosteno.core

/**
 * Все пороги конвейера в одном месте, чтобы bench мог перебирать их.
 * Значения — стартовые, подбираются только на testdata/dev.
 */
data class PipelineConfig(
    /** Разрыв реплики одного спикера при паузе больше этого значения. */
    val pauseSplitMs: Long = 1000,
    /** Поправка границ реплики к границе VAD-сегмента (не дальше этого значения). */
    val edgePadMs: Long = 150,
    /** Насколько можно продлить конец / сдвинуть начало реплики к границе VAD-сегмента. */
    val vadExtendEndMs: Long = 1000,
    val vadExtendStartMs: Long = 400,
    /** Слово без перекрытия с турнами привязывается к ближайшему турну не дальше этого расстояния. */
    val nearestTurnMaxMs: Long = 300,
    /** Короткие ответы, которые не «съедаются» сглаживанием, если у них есть собственный турн. */
    val backchannelWords: Set<String> = setOf("да", "нет", "угу", "ага", "ну", "мм", "ок", "хорошо"),
    val backchannelMinTurnMs: Long = 300,
    /** Любое слово с собственным турном не короче этого не переходит к соседям при сглаживании. */
    val ownTurnProtectMs: Long = 700,
    /** Реплика из одного токена короче этого и с низкой уверенностью считается неречью. */
    val nonSpeechMaxSingleTokenMs: Long = 300,
    val nonSpeechMinConf: Float = 0.5f,
    /** Максимальная длина ASR-чанка и паддинг. */
    val maxChunkMs: Long = 25_000,
    val chunkPadMs: Long = 200,
    /** Диапазон числа спикеров для автоопределения. */
    val minSpeakers: Int = 1,
    val maxSpeakers: Int = 8,
    /** Порог косинусной близости центроидов для слияния кластеров. */
    val mergeClusterCosine: Float = 0.75f,
    /** Кластер, суммарно говорящий меньше, растворяется в соседях (защита от «шумовых спикеров»). */
    val minSpeakerTotalMs: Long = 1500,
    /**
     * Постобработка кластеров диаризации по эмбеддингам чистых окон (ClusterPost, docs/research/2026-11-07-cluster-post.md):
     * слияние кластеров, которые эмбеддинг не различает (cross/within > clusterPostMergeRatio, у обоих
     * ≥ clusterPostMinWindows окон). Подобрано на dev (плато 0.96–0.98, окна 3–5), проверено на test.
     */
    val clusterPostEnabled: Boolean = true,
    /** Диаризация NVIDIA Nemotron-3-Diarization вместо pyannote+CAM++ (с откатом на них при ошибке). */
    // Включено 2026-11-07: Pixel 6a live_test DER 55.7 → 21.5, RTF 0.75 → 0.41 (исключение из правила 9 — см. ADR-0003).
    val nemotronDiarizer: Boolean = true,
    val clusterPostMergeRatio: Float = 0.97f,
    val clusterPostMinWindows: Int = 3,
    /** Слияние по косинусу центроидов (>= 1 — выкл.): на dev вредит (похожие голоса cos 0.8–0.93). */
    val clusterPostMergeCos: Float = 1.1f,
    /**
     * Мелкие кластеры (< мс / < доли речи) — в ближайший по голосу. Выключено: на dev DER 33.7→31.7,
     * но cpWER speakers=5 +2.0 п.п. (критерий ≤ 1 п.п.).
     */
    val clusterPostTinyMs: Long = 0,
    val clusterPostTinyFrac: Float = 0f,
    /** Бюджет LLM на файл как доля длительности аудио (0.3 = 30% от длительности). */
    val llmBudgetFraction: Double = 0.3,
    val llmEnabled: Boolean = true,
    /** Файлы длиннее — ASR и диаризация последовательно (экономия пиковой RAM на телефоне). */
    val parallelMaxMs: Long = 15 * 60_000L,
    /** Динамическая нормализация громкости речи (SpeechAgc) — только для входа ASR. */
    val agcEnabled: Boolean = false,
    val agcMaxGain: Float = 8f,
    /** Повторная кластеризация окон pyannote + CAM++ после ClusterPost. */
    val reclusterEnabled: Boolean = true,
    val reclusterGuardWindows: Int = 15,
    val reclusterMinWindows: Int = 10,
    val reclusterSilMin: Float = 0.1f,
) {
    fun reclusterConfig(): Recluster.Config? = if (reclusterEnabled) Recluster.Config(
        method = Recluster.Method.AHC_SIL,
        assign = Recluster.Assign.TURN,
        guardWindows = reclusterGuardWindows,
        minWindows = reclusterMinWindows,
        silMin = reclusterSilMin,
        minSpeakers = minSpeakers,
        maxSpeakers = maxSpeakers,
    ) else null
}
