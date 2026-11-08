# Нейро-стенографист (Nuclear IT Hack, Android)

Android-приложение, которое **полностью на устройстве, без интернета** расшифровывает разговор: аудио → русская речь в текст (GigaAM v3) → определение говорящих (Nemotron-3-Diarization) → реплики с временными метками → локальная LLM (llama.cpp, пунктуация под WordGuard, протокол встречи) → просмотр с воспроизведением и экспорт JSON.

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
```

## Отчётные материалы (слайд 20)
Пакет v0.2.0 и состояние его проверок — [docs/RELEASE-0.2.0.md](docs/RELEASE-0.2.0.md). Файлы выпуска публикуются на [странице v0.2.0](https://github.com/Shalioner/khakhakhakhakaton-android/releases/tag/v0.2.0).

| # | Материал | Где |
|---|---|---|
| 1 | APK-файл | `neurosteno-v0.2.0-arm64.apk` (~1.1 ГБ, arm64-v8a) — [выпуск v0.2.0](https://github.com/Shalioner/khakhakhakhakaton-android/releases/tag/v0.2.0) |
| 2 | Исходный код | [src/android](src/android) |
| 3 | Инструкция по установке и использованию | [src/README.md](src/README.md) |
| 4 | Архитектура и модели | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [ADR-0001 модели](docs/ADR/0001-models.md), [ADR-0002 LLM](docs/ADR/0002-llm.md), [ADR-0003 Nemotron](docs/ADR/0003-nemotron.md) |
| 5 | Ограничения | [docs/LIMITATIONS.md](docs/LIMITATIONS.md) |
| 6 | Результаты собственных тестов | [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md), [artifacts/test-results](artifacts/test-results) |
| 7 | Видео демонстрации | [artifacts/demo](artifacts/demo); `demo-v0.2.0-emulator.mp4` — [выпуск v0.2.0](https://github.com/Shalioner/khakhakhakhakaton-android/releases/tag/v0.2.0) |

## Соответствие требованиям
| Требование (слайды 9–15) | Реализация |
|---|---|
| Android OS | minSdk 28, arm64-v8a |
| Локальная LLM | llama.cpp + Qwen2.5-0.5B Q4_K_M в APK: пунктуация неоформленных реплик (под WordGuard) и протокол встречи |
| Импорт аудио из памяти устройства | системный выбор файла (SAF), «Поделиться»; wav/mp3/m4a/ogg/opus/flac/amr |
| Запись из интерфейса | кнопка «Записать с микрофона» |
| Речь → текст | GigaAM v3 RNNT (sherpa-onnx), два прохода: слова (числа словами) + перенос пунктуации |
| Определение и разделение говорящих | Nemotron определяет говорящих автоматически. При ошибке используется pyannote + CAM++ |
| Временные метки реплик | из таймстемпов токенов ASR и границ VAD, в мс от начала файла |
| Отображение | история записей на устройстве, плеер, цвет говорящего, время, тап = воспроизведение |
| Экспорт в файл | JSON строго по контракту (проверяется `ContractValidator`) |
| Без интернета, без облака | нет разрешения INTERNET, все модели внутри APK |

## Быстрый старт
```bash
python tools/fetch_models.py
git clone https://github.com/ggml-org/llama.cpp src/android/engine-llm/src/main/cpp/llama.cpp
git -C src/android/engine-llm/src/main/cpp/llama.cpp checkout 669204ee2b9004845b34f26466a91d003edc98aa
cd src/android && ./gradlew :core:test :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
Установка поверх существующего приложения возможна только при совпадении подписи. Mac-сборка v0.2.0 использует другой ключ, чем опубликованная v0.1.0; порядок установки и предупреждение о сохранности записей — в [инструкции](src/README.md#установка-готового-apk).

Подробно — [src/README.md](src/README.md). Правила разработки — [AGENTS.md](AGENTS.md), задание — [docs/TASK.md](docs/TASK.md).

## Структура
```
src/android/   Gradle-проект: core (чистый Kotlin), engine-audio, engine-sherpa, engine-nemotron, engine-llm (llama.cpp JNI), app (Compose), bench (ПК-харнесс)
tools/         скачивание моделей, метрики (WER/cpWER/DER/границы/RTF), генерация синтетических тестов
testdata/      эталонные JSON наших тестов (dev — подбор, test — контроль); аудио генерируется скриптом
docs/          архитектура, ограничения, результаты, ADR
artifacts/     результаты тестов и материалы демонстрации
config/        документированные параметры конвейера
```
