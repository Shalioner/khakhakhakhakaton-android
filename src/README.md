# Исходный код

- `android/` — Gradle-проект приложения (модули `core`, `engine-audio`, `engine-sherpa`, `engine-nemotron`, `engine-llm`, `app`, `bench`). Архитектура — [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Установка готового APK

1. Скачать `neurosteno-v0.2.0-arm64.apk` со [страницы v0.2.0](https://github.com/Shalioner/khakhakhakhakaton-android/releases/tag/v0.2.0).
2. Требуется Android 9+ и процессор arm64-v8a. Предусмотреть не менее 3.5 ГБ свободного места для APK, установки и копий моделей.
3. Открыть APK на устройстве и при запросе разрешить установку из выбранного источника. Через ADB из каталога со скачанным файлом:

```bash
adb install neurosteno-v0.2.0-arm64.apk
```

**Совместимость подписи:** Mac-сборка v0.2.0 подписана другим ключом, чем опубликованная v0.1.0, и поверх неё не устанавливается. Если v0.1.0 уже установлена, сначала экспортировать нужные расшифровки и отдельно сохранить доступные исходные аудиофайлы. Самостоятельное удаление приложения стирает локальную историю и внутренние аудиофайлы; экспорт JSON сохраняет текст, но не аудио и не всю историю приложения. Не удалять прежнюю установку, пока необходимые данные не сохранены.

Все модели находятся внутри APK. Приложение не загружает их при первом запуске и не требует интернета. Mac-сборка на физическом телефоне пока не проверена; актуальные результаты и ограничения — [в отчёте v0.2.0](../docs/RELEASE-0.2.0.md).

## Использование

1. Открыть «Нейро-стенографист» → «Выбрать аудиофайл» либо передать аудио через «Поделиться» из другого приложения. Для записи с микрофона выбрать соответствующую кнопку и предоставить разрешение на запись звука.
2. Дождаться обработки. Экран показывает этапы и прогресс, обработку можно отменить; она продолжается в фоне.
3. На экране расшифровки тап по реплике включает воспроизведение с её начала. Тап по метке говорящего меняет отображаемое имя; долгое нажатие на реплику открывает правку текста.
4. «Экспорт JSON» сохраняет расшифровку через системный диалог. Исправленный текст попадает в экспорт, а говорящие сохраняют контрактные идентификаторы `speaker_N`: отображаемые имена в JSON не добавляются.
5. «Протокол» создаёт краткий протокол от локальной LLM. Его вывод может содержать неточности. Запись доступна для повторного открытия из истории.

## Инструкция по запуску и сборке проекта

### Требования
- JDK 17+ (проверено на OpenJDK 21), Android SDK: platform 35, build-tools 35.0.0, NDK 27.2.12479018, CMake 3.22.1.
- Python 3.10+ (скачивание моделей, метрики): `python -m pip install -r tools/requirements.txt`.
- Место для моделей, исходников llama.cpp и результатов сборки; SDK/NDK и кэши Gradle требуют дополнительного места.

### 1. Модели и библиотеки (один раз, нужен интернет только на машине сборки)
Команды выполняются из корня репозитория.

```bash
python tools/fetch_models.py            # sherpa-onnx AAR/JAR + модели, включая Nemotron, в src/android/app/src/main/assets/models
git clone https://github.com/ggml-org/llama.cpp src/android/engine-llm/src/main/cpp/llama.cpp
git -C src/android/engine-llm/src/main/cpp/llama.cpp checkout 669204ee2b9004845b34f26466a91d003edc98aa
```

### 2. Сборка APK
Из корня репозитория:

```bash
cd src/android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :core:test :engine-nemotron:assembleRelease :app:assembleRelease
# APK относительно текущего каталога: app/build/outputs/apk/release/app-release.apk
# Размер ~1.1 ГБ, arm64-v8a
```

### 3. Установка и использование
После сборки, оставаясь в `src/android/`:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```
Флаг `-r` сохраняет данные при обновлении совместимой подписью. В проекте release подписывается локальным debug-ключом: сборки на разных машинах могут иметь разные подписи. Сценарий работы и ограничение Mac-сборки описаны выше.

### 4. Прогон тестов
Команды для ПК ниже выполняются из корня репозитория. ПК bench использует pyannote + CAM++; Nemotron в Android APK проверяется отдельно. До пересборки неисправной `neurollm.dll` использовать `--llm none` (см. [ограничения](../docs/LIMITATIONS.md)).

```bash
# ПК (Windows): LLM-библиотека для bench, затем прогон и отчёт
tools\build_llm_windows.cmd
(cd src/android && ./gradlew :bench:run --args="testdata/dev out/dev --llm none")
python tools/report.py testdata/dev out/dev --timings out/dev/timings.json

# Телефон: положить wav/mp3 в /sdcard/Android/data/ru.neurosteno.app/files/bench/
adb shell am start -n ru.neurosteno.app/.BenchActivity
adb pull /sdcard/Android/data/ru.neurosteno.app/files/bench/out out/phone
```
