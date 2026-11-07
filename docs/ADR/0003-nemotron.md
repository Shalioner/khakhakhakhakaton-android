# ADR-0003: Диаризация NVIDIA Nemotron-3-Diarization через свой JNI к onnxruntime C API

Статус: модуль собран, в `Pipeline` не подключён (ждёт замеров на телефоне), 2026-11-07.

## Контекст
Спайк `docs/research/2026-11-07-nemotron-spike.md`: на живых русских звонках (DataHive, 8 файлов) текущая диаризация
(pyannote-seg-3.0 + CAM++ + FastClustering) даёт DER после `core` 75.9 % в среднем и 112.7 % в худшем случае и находит
6–33 спикера на 2 собеседника. У Nemotron-3-Diarization DER 23.9 / 41.1 %, спикеров 2–4. На синтетике не хуже нигде,
кроме `dev/clean_5spk_000` (32.0 → 50.1 %; это копии одного TTS-голоса со сдвигом высоты). В спайке выбран
**вариант C**: свой тонкий JNI к ORT, который уже есть в APK, а признаки, чанки и кэш спикеров — на Kotlin в `core`.
Тест на телефоне пройден: Pixel 6a, Android 17 (`docs/TEST_RESULTS.md`, раздел «Телефон»).

## Решение
- **Модель**: неофициальный ONNX-экспорт `NealCaren/Nemotron-3-Diarization-ONNX`. Файлы кладутся без изменений в
  `assets/models/nemotron/` скриптом `tools/fetch_models.py`, SHA-256 сверяется:
  | файл | байт | sha256 |
  |---|---|---|
  | `step_int8.onnx` (энкодер + голова, dynamic int8; IR 8, opset 17) | 103 325 385 | `1f18a5ae…0a9d2a` |
  | `embed.onnx` (stacking ×8 + проекция) | 2 102 458 | `2e8796e4…a84a33`* |
  | `mel_filters.bin` (float32 [128, 257], Slaney) | 131 584 | `bce5ec5f…9137` |
  | `silence_embeds.bin` (float32 [512]) | 2 048 | `d4417b3c…c4ac7` |
  | `LICENSE` (OpenMDW-1.1) | 2 619 | `2ab44b68…74df` |
  Полные хэши — в `tools/fetch_models.py`. В спайке опубликован только хэш `step_int8.onnx`. Остальные посчитаны по
  файлам из `.cache/models/nemotron/nealcaren`, на которых спайк мерил DER. Хэш `silence_embeds.bin` совпал с LFS oid
  на HF (`X-Linked-ETag`). *Звёздочка — хэш взят из локального файла, с HF напрямую не сверялся.
- **Лицензия — OpenMDW-1.1**: разрешает использование и распространение, в том числе коммерческое. При распространении
  нужно приложить текст лицензии и сохранить уведомления об авторстве. Поэтому рядом с моделью в assets лежат `LICENSE`
  и `NOTICE` («model by NVIDIA», ссылки на оригинал и на экспорт). Их кладёт `fetch_models.py` в
  `app/src/main/assets/models/nemotron/`. Копия, которая хранится в git, лежит в
  `engine-nemotron/src/main/assets/models/nemotron/{LICENSE,NOTICE}`; при сборке побеждает asset app, содержимое
  одинаковое. Те же сведения нужно внести в MODELS.md (docs-release).
- **Рантайм**: своей ORT нет. `libonnxruntime.so` (ORT **1.28.2**, экспортирует `OrtGetApiBase`) уже лежит в APK из
  `sherpa-onnx-2.0.1.aar`. `engine-nemotron` зависит от того же `:sherpa-onnx-aar`, поэтому в APK одна копия ORT, и
  `pickFirst` не нужен.
  - `libneurort.so` (C++17, около 230 строк, `src/main/cpp/neurort_jni.cpp`) **не линкуется** с ORT: в `DT_NEEDED`
    только `liblog`, `libdl`, `libc`. Она делает `dlopen("libonnxruntime.so")` и `dlsym("OrtGetApiBase")`, а
    `GetApi(28)` вызывает с откатом до версии 16 (структура OrtApi только дополняется). Kotlin заранее вызывает
    `System.loadLibrary("onnxruntime")`, и `dlopen` получает уже загруженный экземпляр. Почему не IMPORTED-линковка:
    AGP сам упаковывает импортированные .so. Тогда в APK попали бы две `libonnxruntime.so` (из AAR и из CMake), и
    понадобился бы `pickFirst`. Кроме того, сборка зависела бы от распаковки AAR.
  - API: `create(path, threads)`, `inputNames`, `outputNames`, `run(names, float[][], long[][])` → выходы float32,
    `release`. Ошибки ORT превращаются в `RuntimeException`. Входы копируются в нативный буфер на время `Run`.
    Сессия: intra-op = `numThreads` (embed — 1 поток), inter-op 1, `ORT_ENABLE_ALL`.
  - Заголовки ORT C API v1.28.2 (`onnxruntime_c_api.h`, `onnxruntime_ep_c_api.h`, `onnxruntime_error_code.h`) взяты из
    `github.com/microsoft/onnxruntime` (tag `v1.28.2`, `include/onnxruntime/core/session/`). Лицензия MIT, её текст
    лежит рядом: `engine-nemotron/src/main/cpp/third_party/onnxruntime/LICENSE`. Это только заголовки, бинарников ORT в
    git нет. sha256 `onnxruntime_c_api.h` = `b69a1331…5c1`.
  - Сборка: NDK 27.2, CMake 3.22.1, `c++_static`, `-Wl,-z,max-page-size=16384`: все LOAD-сегменты выровнены по 0x4000,
    проверено `llvm-readelf`. ABI: `arm64-v8a`, `x86_64` для эмулятора в debug; release app фильтрует до arm64.
- **Логика в `core`** (`ru.neurosteno.core.nemotron`, чистый Kotlin, тестируется на JVM):
  - `NemotronFrontend` — признаки ровно как `Frontend` в `tools/spikes/nemotron_diarize.py`: преэмфаза 0.97,
    STFT center=True (нулевой паддинг 256), n_fft 512, симметричный Hann 400 по центру 512, hop 160, |X|² @ mel,
    `log(x + 2⁻²⁴)`, без нормализации, `num_frames = len / 160`. Кадры считаются блоками из `SampleSource`
    (`PcmFile.read`), так что весь файл во float в RAM не нужен (SherpaDiarizer держит его целиком). Типы
    float32/float64 повторяют numpy-спайк.
  - `NemotronStreamer` — `Nemotron.probs()` и `to_turns()` спайка: чанк 340 эмбеддинг-кадров (27.2 с) + правый
    контекст 40 (3.2 с), вход шага = [кэш спикеров 264, FIFO 40, чанк + контекст], ≤ 684 кадра.
    `SpeakerCache` — AOSC: компрессия по score, порог 0.25, boost 0.05, strong 0.75, weak 1.5, 1 тихий кадр на
    спикера. Постобработка: порог активности 0.5, склейка пауз ≤ 0.5 с, отброс турнов ≤ 0.2 с, спикеры по первому
    появлению, перекрытия сохраняются. Отмена проверяется между чанками, прогресс отдаётся по чанкам.
  - `OnnxSession` (`run(Map<String, FloatTensor>) → Map`) — граница с нативом. В тестах вместо сетей фейки.
- **Android**: `NemotronDiarizer(context, numThreads = 4): AutoCloseable`,
  `run(pcm, onProgress, isCancelled): List<SpeakerTurn>` — та же сигнатура, что у `SherpaDiarizer.run`. ONNX-файлы
  один раз копируются из assets (noCompress) в `filesDir/nemotron/`; повторное копирование — только если размер
  другой. `mel_filters.bin` и `silence_embeds.bin` читаются из assets в память. Если моделей нет или ORT не
  загрузилась, конструктор бросает исключение, и вызывающий переключается на fallback.

## Проверка эквивалентности (JVM, `./gradlew :core:test`, `NemotronTest`, 7 тестов)
Эталон генерирует `tools/spikes/nemotron_kotlin_ref.py`: он импортирует код спайка и подменяет сети детерминированными
фейками, которые повторены в тесте 1:1. Сигнал — целочисленный LCG длиной 100 с: тишина и три «голоса» (меандры и
пила с шумом). Ресурсы — `core/src/test/resources/nemotron/`, около 270 КБ (из них `mel_filters.bin` модели 128 КБ,
`NOTICE.txt` и `LICENSE-OpenMDW-1.1.txt` лежат рядом).
- признаки (3 окна по 32 кадра, включая окно на хвосте файла): max |Δ| = 1.9e-6, max отн. 3.2e-7;
- стример (4 шага: T = 380, 684, 684, 535; 4 компрессии кэша): **индексы кадров, выбранных каждой компрессией,
  совпали с Python точно**, max |Δp| = 8.3e-7, турны (76 штук, 8 спикеров) совпали точно;
- отдельно проверены: порог, склейка пауз и отброс коротких турнов, нумерация по первому появлению, перекрытия,
  устойчивый `topk` (−0.0 == 0.0, ±inf), пустой и короткий вход, отмена.

## Размеры
- APK release: **1 067 091 511 → 1 172 951 460 Б (+105.9 МБ)**: `step_int8.onnx` 103.3 МБ + `embed.onnx` 2.1 МБ
  (Stored) + `libneurort.so` 0.35 МБ (arm64, stripped) + bin/LICENSE/NOTICE ≈ 5 КБ. Сборка «до» сделана 18:23 из
  другого состояния дерева, поэтому разница включает и чужие правки. Вклад Nemotron, посчитанный по файлам, — 105.8 МБ.
- На диске телефона: +105 МБ (копия ONNX в `filesDir`, как у LLM).
- RAM (ПК, Python): модели +144 МБ RSS, рабочая память 65–100 МБ. Телефон `[Проверить]`.

## Последствия
- `Pipeline` и `BenchActivity` не менялись. Включение и fallback — отдельным патчем после замеров. Fallback-цепочка:
  Nemotron → SherpaDiarizer → один спикер.
- `ClusterPost` (CAM++ по турнам) с Nemotron не проверялся. В спайке `nem_e2e` считался с `base=off`; при включении
  Nemotron его стоит держать выключенным до отдельного A/B.
- bench на ПК (JVM) Nemotron не умеет: ORT for Java мы намеренно не добавляли. A/B на ПК — Python-спайк, а логика
  `core` с ним эквивалентна (см. выше).

## Риски и `[Проверить]`
- RTF и пиковый PSS на Pixel 6a с 2 и 4 потоками, файлы 5 / 30 / 60 мин. По оценке спайка RTF 0.1–0.3, RAM
  +250–350 МБ к процессу.
- Совместимость `step_int8.onnx` (IR 8, opset 17, `com.microsoft` int8-операторы dynamic quantization) именно с ORT
  1.28.2 из сборки sherpa: на ПК проверено с ORT 1.31.1. Если в сборке sherpa урезан набор операторов, `CreateSession`
  упадёт, и сработает fallback.
- Обновление sherpa-onnx AAR меняет ORT под нами. C API совместим назад, `GetApi` откатывается до v16, но после
  обновления нужен повторный прогон.
- Живые записи с 3–5 разными людьми (регрессия `clean_5spk`) — по правилу 9, до включения по умолчанию.
- Перекрытия: Nemotron отдаёт параллельные турны, слово назначается одному спикеру в `Alignment`.
