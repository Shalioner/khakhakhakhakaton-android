# Исходный код

- `android/` — Gradle-проект приложения (модули `core`, `engine-audio`, `engine-sherpa`, `engine-nemotron`, `engine-llm`, `app`, `bench`). Архитектура — [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Инструкция по запуску и сборке проекта

### Требования
- JDK 17+ (проверено на OpenJDK 21), Android SDK: platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.
- Python 3.10+ (скачивание моделей, метрики): `python -m pip install -r tools/requirements.txt`.
- ~4 ГБ свободного места (модели, исходники llama.cpp, сборка).

### 1. Модели и библиотеки (один раз, нужен интернет только на машине сборки)
```bash
python tools/fetch_models.py            # sherpa-onnx AAR/JAR + модели, включая Nemotron, в src/android/app/src/main/assets/models
git clone https://github.com/ggml-org/llama.cpp src/android/engine-llm/src/main/cpp/llama.cpp
git -C src/android/engine-llm/src/main/cpp/llama.cpp checkout 669204ee2b9004845b34f26466a91d003edc98aa
```

### 2. Сборка APK
```bash
cd src/android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :core:test :engine-nemotron:assembleRelease :app:assembleRelease
# APK: src/android/app/build/outputs/apk/release/app-release.apk (~1.1 ГБ, arm64-v8a)
```

### 3. Установка и использование
```bash
adb install -r src/android/app/build/outputs/apk/release/app-release.apk
```
1. Открыть «Нейро-стенографист» → «Выбрать аудиофайл» (или «Поделиться» аудиофайлом из другого приложения).
2. Дождаться обработки (этапы и прогресс на экране, можно отменить; обработка продолжается в фоне).
3. На экране расшифровки: тап по реплике — воспроизведение с этого места; «Экспорт JSON» — сохранить результат; «Протокол» — краткий протокол от локальной LLM.

Интернет приложению не нужен (разрешения INTERNET нет) — можно проверять в авиарежиме.

### 4. Прогон тестов
```bash
# ПК (Windows): LLM-библиотека для bench, затем прогон и отчёт
tools\build_llm_windows.cmd
cd src/android && ./gradlew :bench:run --args="testdata/dev out/dev"
python tools/report.py testdata/dev out/dev --timings out/dev/timings.json

# Телефон: положить wav/mp3 в /sdcard/Android/data/ru.neurosteno.app/files/bench/
adb shell am start -n ru.neurosteno.app/.BenchActivity
adb pull /sdcard/Android/data/ru.neurosteno.app/files/bench/out out/phone
```
