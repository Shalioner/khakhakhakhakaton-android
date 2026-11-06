# ADR-0001: Модели и рантаймы (офлайн, Android arm64, 4–6 ГБ RAM)

Статус: принято для MVP, 2026-11-06. Источник фактов — GitHub releases sherpa-onnx, HF. [Проверить] — не измерено нами.

## Решение
| Компонент | Модель / библиотека | Размер | Лицензия | Примечание |
|---|---|---|---|---|
| Рантайм | sherpa-onnx **v2.0.1** AAR (`sherpa-onnx-2.0.1.aar`) | 49 MB | Apache-2.0 | Для ПК-бенча: `sherpa-onnx-jvm-2.0.1.jar` + `sherpa-onnx-native-lib-win-x64-2.0.1.jar` |
| VAD | `silero_vad.onnx` (tag `asr-models`) | 0.6 MB | MIT [Проверить] | Сегменты в сэмплах |
| ASR | **GigaAM v3 e2e RNNT int8** `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16` | 170 MB (encoder 225 MB) | MIT | Потокенные timestamps; сразу пунктуация и регистр; `modelType="nemo_transducer"` |
| ASR (A/B) | GigaAM v3 CTC `sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16` | 163 MB | MIT | CTC-таймстемпы по кадрам, возможно точнее для границ |
| Сегментация | `sherpa-onnx-pyannote-segmentation-3-0` | 7 MB | MIT | |
| Эмбеддинги | `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` | 28 MB | Apache-2.0 [Проверить] | Русской модели нет, языконезависимая — проверить на bench |
| Кластеризация | FastClustering `numClusters=-1` + threshold | — | — | Автоопределение числа спикеров, порог подбирается на dev |
| LLM | llama.cpp + **Qwen2.5-0.5B-Instruct Q4_K_M** (491 MB, Apache-2.0); A/B Qwen3-0.6B Q4_K_M (397 MB) | ~0.5 GB | Apache-2.0 | Телефон 4–6 ГБ: модель ≤ 0.8B. Только регистр/пунктуация через WordGuard; запуск после выгрузки ASR/диаризации |

## Ключевые факты
- WER из карточки GigaAM-v3 (среднее по 5 наборам): v3_rnnt 8.4, v3_ctc 9.2, Whisper 25.1. Для e2e-вариантов WER не опубликован → A/B на нашем bench обязателен.
- GigaAM v1 (2024-10-24) — некоммерческая лицензия, **не использовать**. reverb-diarization — license:other, не использовать.
- Русских punctuation-моделей в sherpa-onnx нет; дешёвая альтернатива LLM — e2e GigaAM v3.
- `OfflineRecognizerResult` содержит `tokens[]`, `timestamps[]` (сек) — слова собираем склейкой BPE-токенов по «▁».
- Диаризация: `OfflineSpeakerDiarization.process(FloatArray)` → сегменты `(start, end, speaker)`; эмбеддинги отдельно через `SpeakerEmbeddingExtractor` (для слияния кластеров по центроидам).
- llama.cpp: `examples/llama.android`, собственная CMake-сборка под NDK с `-DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF`.

## Риски
- Точность таймстемпов RNNT против CTC [Проверить на bench].
- Пиковая RAM при одновременной диаризации и ASR на 4 ГБ [Проверить на телефоне] → fallback: последовательный запуск.

## Источники
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1 , /asr-models , /speaker-segmentation-models , /speaker-recongition-models
- https://github.com/k2-fsa/sherpa-onnx/tree/master/sherpa-onnx/kotlin-api
- https://github.com/salute-developers/GigaAM , https://huggingface.co/ai-sage/GigaAM-v3
- https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF , https://huggingface.co/unsloth/Qwen3-0.6B-GGUF
- https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
