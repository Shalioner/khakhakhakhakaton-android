# Исследование: данные для теста и готовые решения (2026-11-07)

Роль: researcher. Только факты с источниками; `[Проверить]` — не подтверждено или не измерено нами.
Метод: README/карточки HF, HF API (`/api/datasets`, `datasets-server /info /rows`), GitHub API, исходники sherpa-onnx master (после v2.0.1). Большие файлы не скачивались.
Контекст: текущие проблемы из `docs/TEST_RESULTS.md` и `docs/LIMITATIONS.md`: (1) похожие голоса сливаются (`same_base`, DER ~50 %); (2) перекрытия; (3) e2e пишет числа цифрами; (4) границы реплик (DER целиком из miss, граница в среднем 1189 мс, а на `distinct` 83 мс).

---

## Главные выводы (коротко)

1. **Ошибка границ на test почти целиком вызвана слиянием спикеров**, а не таймстемпами: на `distinct` граница 83 мс, на `same_base` 4184 мс (TEST_RESULTS). Если чинить диаризацию, тайминги улучшатся вместе с ней.
2. **Ось `same_base`** (один TTS-голос со сдвигом высоты) — атипичный стресс-тест. Подбирать пороги под неё опасно. Нужны настоящие похожие голоса: актёры Langswap Dialogs (2 женщины и мужчина, 12 стилей), 4 голоса Piper ru, живые стерео-диалоги DataHive.
3. **В sherpa-onnx кластеризация — complete linkage по косинусу с одним порогом, без min_cluster_size** (`fast-clustering.cc`: `HCLUST_METHOD_COMPLETE`, `cutree_cdist`). В pyannote 3.1 есть min_cluster_size и переназначение мелких кластеров, в community-1 — VBx. У Cassini на Pixel 8 этот же стек по порогу переоценил число спикеров: 7 вместо 4 и 3 вместо 2.
4. **Появилась диаризация без порога: Nemotron-3-Diarization** (NVIDIA, 2026-09-23, 100M параметров, до 8 спикеров, лицензия OpenMDW-1.1 разрешает коммерческое использование). Есть ONNX int8 на 103 МБ и **открытый PR в sherpa-onnx #4119/#4032**, проверенный на Android (cassini-android). Сама модель размечает перекрытия. Русский язык в карточке не заявлен `[Проверить]`.
5. **Для WER слова из модели без пунктуации — правильный выбор**: в официальной таблице GigaAM у v3_rnnt средний WER 8.3, у v3_e2e_rnnt 11.2 даже после нормализации (Golos Crowd: 2.4 против 9.1). Как будут записаны числа в эталоне жюри, неизвестно: **это нужно спросить у организаторов** (0 часов кода, на кону до 40 баллов).
6. **Silero VAD v6.2 (2025-11-06)** заметно лучше на «cartoon voices, unusual voices, child voices». Это прямо про «котиков». В sherpa-onnx `asr-models` лежит `silero_vad.onnx` на 0.6 МБ от 2025-07-11 — версия неизвестна `[Проверить]`.
7. В sherpa-onnx уже есть **modified_beam_search и hotwords для офлайн NeMo-transducer** (GigaAM), **Kotlin-параметр `windowShiftRatio`** для сегментации, **`computeConfidence` (силуэт) для сегментов диаризации** и **ITN через `ruleFsts`**. **Слияния с LM в офлайн Kotlin API нет** (`// var lmConfig ... TODO(fangjun): enable it`).

---

## A. Данные

### A1. Русские наборы с разметкой дикторов и времени

| Набор | Что есть | Дикторы / время | Размер | Лицензия | Доступ | Годится для |
|---|---|---|---|---|---|---|
| **DataHive Russian Conversations (sample)** `datahiveai/russian-conversations-sample` | 20 **живых неподготовленных** разговоров знакомых людей по телефону, стерео: **звонящий в левом канале, принимающий в правом**, общая шкала времени | 17 дикторов. Метаданные: overlap % (у нас посчитано: min 4.9 / **медиана 15.0** / max 20 %), пауза между репликами (медиана 144 мс, min −193 мс), темп 4.8–17.2 реплики/мин. **Транскриптов нет** | 3 ч 01 мин, MP3 48 кГц 128 кбит/с, 2.5–35 мин на файл | **CC BY 4.0** | без регистрации, `preview/ru_0000NN.mp3` + `metadata.jsonl` | **Лучший бесплатный источник реальных русских диалогов с перекрытиями**: эталон диаризации строится VAD по каждому каналу, моно — сумма каналов. WER только по псевдоэталону `[Проверить утечку между каналами]` |
| **Langswap «Dialogs»** `langswap/dialogs-ru-emotional-conversations` | Студийные реплики **актёров театра** из сценарных диалогов (Interspeech 2026) | 3 диктора: Masha (Ж, 9.9 ч), Sveta (Ж, 4.4 ч), Dima (М, 6.2 ч). 12 стилей: neutral, happy, sad, angry, **whisper, laughing, arrogance**, fear, poem, tongue-twister… Поле `speaker_id` | 20.6 ч, 11 796 реплик, WAV 44.1 кГц. `preview.parquet` 29 МБ (60 реплик) | OpenRAIL (responsible use, коммерция разрешена) | без регистрации, файлы `wavs/…` скачиваются по одному | **Похожие голоса (две женщины) и стилизованная/актёрская речь** — ближе всего к неизвестным «котикам». Склейка в диалоги даёт точную разметку |
| **synthetic-speech-diarization-ru** `ivkond/…` (копия `niobures/…`) | Синтетические диалоги/монологи с полями `speakers[{speaker_id,start,end,text}]`, `has_overlaps`, `simultaneous_segments`, `noise_type`, `snr` | 2000 треков; пример — 2 спикера, около 40–46 с | 2.74 ГБ parquet (шарды 0..N) | MIT | без регистрации | Быстрый готовый русский набор для диаризации. **Внимание:** в примере `start=9.70, end=13.23, duration=2.73` не сходятся → точность меток сомнительна `[Проверить]`. Источник голосов не указан `[Проверить]`; тексты похожи на голосовые команды |
| Golos (SberDevices) | Crowd 1095 ч + Farfield 132 ч, test 12.6 ч | **ID дикторов в HF-версии нет** (`audio, transcription`), в оригинальных манифестах тоже не заявлены `[Проверить]` | opus 20.5 ГБ; `test.tar` 1.3 ГБ | собственная лицензия Сбера (PDF) | CDN без регистрации | Только ASR и только с пометкой in-domain для GigaAM (обучалась на нём) |
| SOVA RuDevices `bond005/sova_rudevices` | ~94 ч живой речи с устройств | «Any additional information about the speaker… is not provided» | 6.4 ГБ parquet | CC BY 4.0 | HF | ASR, условие «телефон» |
| Common Voice ru | `client_id` = диктор (в TSV) | есть ID дикторов, но все записи — одиночные фразы | ~3.3 тыс. ч (CV20, по Habr) `[Проверить]` | CC0 | **с октября 2025 только через Mozilla Data Collective (аккаунт + ToS)**; на HF `mozilla-foundation/common_voice_17_0` пустой | Склейка «разные дикторы одного пола» для синтетических диалогов |
| FLEURS ru_ru `google/fleurs` | 2562 / 356 / 775 реплик | **ID диктора нет**, только `gender` | 1.86 / 0.25 / 0.58 ГБ | CC BY 4.0 | HF parquet | ASR (чтение) |
| RuLS (OpenSLR 96) | LibriVox, ~98 ч | разметка по дикторам не описана на странице `[Проверить]` | 9.1 ГБ | Public Domain (США) | OpenSLR | ASR, длинные монологи |
| OpenSTT | ~20 тыс. ч (радио, YouTube, звонки) | меток дикторов нет («publish speaker labels» в планах с 2019) | большой | **CC BY-NC 4.0** | GitHub/Azure | только внутренний ASR-тест |
| mTEDx ru (`deepdml/mtedx`) | TEDx-доклады | доклад ≈ один диктор | — | CC BY-NC-ND 4.0 | HF | монологи |
| Podlodka `bond005/podlodka_speech` | 107 фрагментов подкаста, поля `episode`, `title` | меток дикторов нет `[Проверить]` | 0.2 ГБ | не указана `[Проверить]` | HF | живой многоголосый ASR |
| Dusha (Сбер) | ~350 ч, эмоции | меток дикторов нет | 23.5 ГБ (HF `xbgoose/dusha`) | лицензия Сбера | HF/CDN | не для диаризации |
| ESpeech-podcasts, YodaLingua-Russian | подкасты, «turn-taking» | — | — | CC BY-NC / gated | **gated** | не брать |

**Нерусские наборы для проверки диаризации** (готовые HF parquet, по одному файлу на строку: `audio, timestamps_start, timestamps_end, speakers`):

| Набор | Сплиты | Лицензия | Перекрытия |
|---|---|---|---|
| VoxConverse `diarizers-community/voxconverse` | dev 216 (2.34 ГБ), test 232 (5.0 ГБ) | CC BY 4.0 | есть, YouTube-дебаты/новости |
| AMI `diarizers-community/ami` (ihm/sdm) | test 16 встреч (1.04 ГБ) | CC BY 4.0 | много, встречи по 4 человека |
| AliMeeting (OpenSLR 119) | Eval 8 сессий (3.42 ГБ), Test 20 (8.9 ГБ), 2–4 участника | CC BY-SA 4.0 | «разная доля перекрытий» (цифр нет) |
| CALLHOME `diarizers-community/callhome` | jpn/zho/deu/spa/eng, **русского нет** | gated `[Проверить]` | телефонные диалоги |
| MSDWild | видео в естественных условиях, RTTM | `[Проверить]` лицензию; языки (en, zh, th, ko, ja, de, pt, ar) — по сторонней таблице M3SD | есть |

Русского набора с ручной разметкой «кто когда говорит» в открытом доступе **не нашлось**. Ближе всего к нему DataHive: эталон получается из раздельных каналов.

**Синтетические голоса для своих склеек** (без регистрации): Piper ru в sherpa-onnx `tts-models` — `ruslan`, `irina`, `dmitri`, `denis` (int8 по 21 МБ), плюс `vits-mms-rus` (108 МБ). Вместе с двумя голосами Windows TTS получается 7 разных голосов вместо сдвигов высоты одного.

### A2. Шумы и импульсные отклики

| Набор | Содержимое | Размер | Лицензия | Ссылка |
|---|---|---|---|---|
| MUSAN (SLR17) | музыка, речь, шум | 11 ГБ | CC BY 4.0 | https://openslr.trmal.net/resources/17/musan.tar.gz |
| RIRS_NOISES (SLR28) | реальные и симулированные RIR, изотропные и точечные шумы (из MUSAN), 16 кГц | **1.3 ГБ** | Apache-2.0 | https://openslr.trmal.net/resources/28/rirs_noises.zip |
| DEMAND (Zenodo 1227121) | 18 сред (кафе, метро, кухня, улица…), **каждая среда отдельным zip на 16 кГц, 78–130 МБ** | 16k-версии всего ≈1.9 ГБ | CC BY 4.0 | https://zenodo.org/records/1227121 |
| **ESC-50** | 2000 клипов по 5 с, 50 классов, **`cat` = target 5, 40 клипов**; есть dog, rooster, cow, laughing, crying baby | ~600 МБ zip | **CC BY-NC** (только внутренние тесты) | https://github.com/karolpiczak/ESC-50 |

Подходящий тест для «котиков»: 40 клипов `cat` (+ `dog`, `laughing`) подмешиваются в паузы dev-диалогов, и проверяется правило 8 (ноль реплик и ноль новых спикеров). Мяуканье, которое ASR распознал как «мяу», тоже даст реплику. Как размечены такие звуки в эталоне жюри, неизвестно `[Проверить]`.

### A3. Как брать подмножества, не скачивая всё

- **Метаданные и схема**: `https://datasets-server.huggingface.co/info?dataset=<id>` (размеры сплитов, поля). Проверено на всех наборах выше.
- **Отдельные строки с аудио**: `https://datasets-server.huggingface.co/rows?dataset=<id>&config=default&split=<s>&offset=N&length≤100`. Для признака `Audio` в ответе приходит **подписанная ссылка `src` на wav** (проверено на Langswap Dialogs). Если аудио хранится как массив float (ivkond), ответ получается огромным, и лучше читать parquet.
- **Шарды parquet**: `https://huggingface.co/api/datasets/<id>/parquet/<config>/<split>` возвращает список `…/0.parquet`, `1.parquet`. Можно скачать один шард или читать нужные row group по HTTP range (pyarrow/duckdb с `hf://`).
- **Отдельные файлы репозитория**: `https://huggingface.co/datasets/<id>/resolve/main/<path>` (так берутся wav из Dialogs и mp3 из DataHive). Для ESC-50 — `meta/esc50.csv` плюс нужные `audio/*.wav` через raw.githubusercontent.
- **Стриминг**: `load_dataset(id, split=…, streaming=True).take(n)`.
- Эндпоинт `/filter?where=…` на `ashraq/esc50` вернул пустой ответ `[Проверить]`.

---

## B. Готовые решения

### B1. Android и офлайн

| Проект | Стек | Что взять |
|---|---|---|
| **cassini-android** (GPL-3.0, обновлён 2026-11-06) | Kotlin, модули `core / data / engine / ui / app / bench` (почти как у нас), sherpa-onnx (форк 1.13.7) + **Nemotron-3-Diarization (Sortformer)**, ASR Parakeet TDT 0.6B v3 | **Код не копировать (GPL), брать только идеи и цифры.** Pixel 8, 2 потока: ASR int8 на 3-минутном клипе 37–40 с, пиковый RSS 2.5 ГБ. Диаризация pyannote+ERes2Net: 56.9 с аудио за 29.8 с (RTF ≈ 0.52), пиковый RSS 284 МБ. По порогу 7 спикеров вместо 4, с известным числом — 4. Нарезка ASR: «целые речевые участки + хвост 0.5 с» дала меньше ошибок, чем окна по 10–15 с (на стыках 10-секундных окон дублировались слова). Сборка AAR из форка: NDK r28c, около 10 мин в CI. Есть Kotlin-обёртка `SortformerStream.kt` и патч `scripts/streaming-diarization.patch` |
| sherpa-onnx `android/SherpaOnnxSpeakerDiarization`, `SherpaOnnxVadAsr` | официальные демо | сверить вызовы `OfflineSpeakerDiarization(assetManager, config)` и `processWithCallback` (прогресс) |
| Transcribro (ISC) | whisper.cpp + Silero VAD | идей для диаризации нет |
| FUTO whisper-acft (MIT) | дообучение Whisper под произвольный `audio_ctx` | к GigaAM неприменимо: конформер и так работает с любой длиной входа |
| WhisperKitAndroid | **архивирован** (2026-01) | не брать |
| Vosk Android | Kaldi, WER хуже GigaAM | не брать |

### B2. Пайплайны «ASR + диаризация + выравнивание»

**Что делает sherpa-onnx v2.0.1** (исходники `offline-speaker-diarization-pyannote-impl.h`, `fast-clustering.cc`, Kotlin API):
- окно сегментации 10 с, сдвиг = `windowShiftRatio` (Kotlin, по умолчанию 0.1 → 1 с; появился в 1.13.6). Больший сдвиг даёт меньше чанков и эмбеддингов, то есть быстрее `[Проверить влияние на DER]`;
- для каждой пары (чанк, локальный спикер) — эмбеддинг; **кадры, где одновременно говорят двое, из эмбеддинга исключаются**; куски короче 10 кадров пропускаются;
- кластеризация: косинусная дистанция, **complete linkage**, обрезка по `threshold` (или `numClusters`);
- реконструкция: число спикеров на кадр × top-k, поэтому **на выходе бывают перекрывающиеся сегменты двух спикеров**, то есть перекрытия в выходе уже есть;
- `minDurationOn` (у нас 0.2) и `minDurationOff` (0.5);
- `computeConfidence=true` (с 1.13.8) — силуэтный коэффициент [-1, 1] на сегмент, −2 = нет данных. Пригоден как детектор плохой кластеризации или «лишнего» спикера;
- `setConfig()` меняет только кластеризацию, но `process()` всё равно пересчитывает сегментацию и эмбеддинги. Перебор порогов на одном файле стоит полного прогона.

**Альтернативы:**

| Решение | Суть | Android/ONNX | Оценка |
|---|---|---|---|
| **Nemotron-3-Diarization** (NVIDIA, 2026-09-23) | end-to-end Sortformer: без эмбеддингов и кластеризации, **до 8 спикеров**, кадр 10 мс, офлайн-буфер 30.4 с, кэш спикеров по порядку появления | ONNX: `NealCaren/Nemotron-3-Diarization-ONNX` (`step_int8.onnx` 103 МБ + `embed.onnx` 2 МБ, ~250 строк JS-обвязки). **sherpa-onnx PR #4032 / #4119 (open, не влит)**: C++, JNI/Kotlin, int8, тесты; Android проверен в cassini-android | DER (карточка, 30.4 с): DIHARD III 12.73 (у Sortformer v2.1 — 19.09), CALLHOME-p2 9.10, AMI SDM 11.14. Лицензия OpenMDW-1.1 (коммерция разрешена). **Русский не заявлен**, обучали на en/zh/hi… `[Проверить на нашем dev]`. RTF на CPU телефона не опубликован `[Проверить]` |
| Streaming Sortformer 4spk v2 | то же, **максимум 4 спикера** | GGUF (NeMo-Speech.cpp), CoreML | CC BY-4.0. Из-за правила 1..8 хуже Nemotron |
| pyannote community-1 | сегментация + WeSpeaker ResNet34 + **VBx** (PLDA), выход **exclusive diarization** (один спикер на кадр — проще выравнивать с ASR) | неофициальный ONNX `altunenes/speaker-diarization-community-1-onnx` (сегментация + эмбеддинг); PLDA/VBx — только Python | DER против 3.1: AMI SDM 19.9/22.7, CALLHOME 26.7/28.5, MSDWild 22.8/25.4. CC BY 4.0, gated. Пороги VBx (диапазоны в `clustering.py`: threshold 0.5–0.8, Fa 0.01–0.5); конкретные значения в закрытом config `[Проверить]` |
| pyannote 3.1 | AHC centroid + `min_cluster_size` (переназначение мелких кластеров в ближайший крупный) | всё уже есть в sherpa, кроме этой логики | По памяти: threshold ≈ 0.7046, min_cluster_size 12 `[Проверить: config gated]`. **Логику min_cluster_size можно перенести в наш `core`** |
| 3D-Speaker (ModelScope) | CAM++ + **спектральная кластеризация с p-pruning (pval 0.02) и eigengap** (число спикеров без порога), `min_cluster_size=4`, слияние по косинусу | только Python, но алгоритм — около 100 строк numpy | DER без детектора перекрытий: VoxConverse 12.1; на CPU RTF 0.03. **Те же эмбеддинги CAM++, что у нас.** Предупреждение из README: на записях < 30 с работает плохо |
| NME-SC (Park et al., arXiv 2003.02405) | спектральная кластеризация с автоподбором p-pruning по нормированному максимальному eigengap, **без dev-набора** | алгоритм (код tango4j) | −17 % относительной ошибки спикеров на CALLHOME. Под правило 9 подходит («адаптивное лучше фиксированного») |
| WhisperX | слово → спикер по **максимальной сумме пересечений** с сегментами; `fill_nearest` — по ближайшему сегменту. Диаризация по умолчанию — community-1 | — | наш `Alignment` устроен так же |
| DiariZen (BUT) | WavLM + EEND | CC BY-NC, тяжёлая | не для телефона |
| NeMo MSDD, reverb-diarization | — | нет офлайн-ONNX под sherpa; у reverb лицензия other | не брать |

**Перекрытия.** ASR выдаёт один поток слов, поэтому слова второго говорящего в зоне перекрытия теряются. Варианты по возрастанию цены:
1. Ничего не восстанавливать, слово отдавать спикеру с бо́льшим пересечением. В зоне перекрытия предпочитать спикера, у которого здесь *начинается* турн (перебивающего); это даёт правильный start_ms его реплики `[Проверить на DataHive]`.
2. Перекрытия брать из Nemotron или pyannote (DER учитывает обоих), слова оставить одному.
3. **VoxLock** (новый в sherpa-onnx v2.0.1, Apache-2.0, 27M, Kotlin API есть): выделяет голос целевого диктора по образцу ровно 3 с. Идея: для каждой зоны перекрытия берётся по 3 с чистой речи каждого спикера, выделяется каждый голос, затем второй проход ASR. Обучен для голосовых агентов и звонков (en?) `[Проверить]`; риск артефактов, которые ухудшат WER.

### B3. Русская постобработка

| Задача | Вариант | Размер / лицензия | Android |
|---|---|---|---|
| ITN (слова → цифры), **только если эталон с цифрами** | NeMo text processing `inverse_text_normalization/ru` (pynini) → скомпилировать в FST → sherpa-onnx `ruleFsts` (kaldifst `TextNormalizer`, тот же механизм, что `itn_zh_number.fst`) | Apache-2.0 | `ruleFsts` есть в Kotlin `OfflineRecognizerConfig`. Свести tagger+verbalizer NeMo в один FST — не тривиально `[Проверить]` |
| TN (цифры → слова) | num2words `lang_RU.py` (LGPL-2.1); NeMo `text_normalization/ru` | — | для склонений («двенадцати») нужен контекст. **Лучше брать слова из модели без пунктуации — так мы и делаем** |
| Пунктуация | **RUPunct_small** (rubert-tiny2, hidden 312, классификация токенов: регистр + `. , ? ! : ; — …`) | 116.5 МБ fp32 pytorch, **MIT**; int8 ONNX ≈ 30 МБ `[Проверить]` | нужен экспорт в ONNX и onnxruntime-android Java. **Возможный конфликт с `libonnxruntime.so` из AAR sherpa** `[Проверить]`. По построению не меняет слова, то есть WordGuard не нужен |
| | RUPunct_medium / big | 340 / 711 МБ, MIT | тяжело |
| | kontur-ai/sbert_punc_case_ru | 852 МБ, Apache-2.0 | тяжело |
| | 1-800-BAD-CODE `punct_cap_seg_47_language` (есть ru, ещё и сегментация предложений) | **ONNX 233 МБ**, Apache-2.0 | тяжело, но готовый ONNX |
| | silero-te | CC BY-NC 4.0 | не брать |
| | **пунктуация из GigaAM e2e (сейчас)** | F1 по GigaAM: «,» 84.5, «.» 86.7, «?» 79.8 | уже есть; стоит второго прохода энкодера |

### B4. Точность GigaAM на телефоне

- **Beam search**: `decodingMethod="modified_beam_search"`, `maxActivePaths` (по умолчанию 4) поддержаны для офлайн NeMo-transducer (`offline-transducer-modified-beam-search-nemo-decoder.h`). Опубликованного выигрыша для GigaAM нет `[Проверить на bench]`; RTF вырастет.
- **Hotwords**: поддержаны там же (`hotwordsFile`, `hotwordsScore=1.5`, `createStream(hotwords)`). **Нам запрещены правилом 9** (подстройка под домен). Нейтральный вариант — не использовать.
- **LM fusion**: в офлайн Kotlin API нет (`lmConfig` закомментирован как TODO). HLG есть только для CTC (v2.0.0). Не делать.
- **Шумоподавление** (GTCRN, DPDFNet, VoxLock есть в sherpa-onnx): данных, что GTCRN/DPDFNet улучшают ASR, у sherpa нет. Свежие исследования показывают ухудшение: MetricGAN+ перед четырьмя ASR повысил semWER на 1.1–46.6 п.п. абсолютных во всех условиях (arXiv 2512.17562); SAM-Audio + Whisper тоже хуже (arXiv 2603.04710). **Для ASR оставить выключенным.** Допустимый A/B: чистить сигнал только для VAD/диаризации, а ASR подавать исходный `[Проверить]`.
- **VAD**: Silero v6.2 (`silero_vad.onnx` 2.33 МБ, MIT) улучшен на нетипичных, детских и мультяшных голосах, тихой речи и плохих звонках. Совместимость входов v6 с `SileroVadModelConfig` sherpa `[Проверить]`. В sherpa есть и TEN VAD (`ten-vad.onnx` 0.3 МБ) `[Проверить лицензию]`.
- **Модель**: GigaAM Multilingual (2026-06, charwise CTC 220M/600M) — в sherpa-onnx её нет; переходить незачем.

---

## C. Что делать: приоритизированный список

Обозначения: Δ — ожидаемый эффект, ч — трудоёмкость, риск — вероятность не получить эффект или сломать другие условия.

| # | Действие | Критерий | Δ (ожидание) | ч | Риск | Источник |
|---|---|---|---|---|---|---|
| 1 | **Спросить организаторов, как нормализуется WER** (числа словами или цифрами, пунктуация, регистр, ё). До ответа оставить слова из RNNT без пунктуации | WER (40) | до ~6 п.п. WER на числах (у нас 7.0 → 0.9 на test) | 0.5 | нет | GigaAM evaluation.md; TEST_RESULTS |
| 2 | **Продлевать `end_ms` реплики до конца турна диаризации / VAD-сегмента**, но не дальше `start_ms` следующей чужой реплики; `start_ms` — симметрично | тайминги (10), DER (25) | DER на 2-спикерной синтетике сейчас полностью из miss, 8.3 % `[Проверить]` | 2–3 | низкий | TEST_RESULTS; sherpa minDurationOn/Off |
| 3 | **Пересобрать ось «похожие голоса»**: Langswap Dialogs (Masha↔Sveta, стили whisper/laughing/arrogance), Piper ru ×4, DataHive (реальные стерео-диалоги, эталон из каналов, перекрытия 5–20 %); `same_base` оставить отчётной, но не выбирать по ней | валидность порогов → DER | — (защита от переобучения) | 3–4 | низкий | HF карточки |
| 4 | **A/B эмбеддингов, которые ещё не пробовали**: `wespeaker_en_voxceleb_CAM++_LM`, `wespeaker_en_voxceleb_resnet34_LM` (её использует community-1), `3dspeaker_campplus_sv_en_voxceleb`, `eres2net_large` (116 МБ), `resnet293_LM` (114 МБ) | DER | `[Проверить]` | 2 (только конфиг) | низкий | sherpa `speaker-recongition-models` |
| 5 | **Пост-кластеризация в `core`**: перекластеризовать с заниженным порогом, затем (а) переназначать мелкие кластеры (< N сегментов или < X с речи) в ближайший крупный, как min_cluster_size в pyannote; (б) сливать центроиды по порогу (уже есть `mergeByCentroids`); (в) `computeConfidence` для флага сомнительных спикеров | DER, защита от лишних спикеров из-за мяуканья/смеха | убирает переоценку числа спикеров (у Cassini 7 вместо 4) | 3–5 | средний | pyannote clustering.py; sherpa PR #3881; cassini pixel8-results |
| 6 | **Go/no-go по Nemotron-3-Diarization на ПК**: ONNX (`NealCaren/...`, int8) + Python-обвязка по образцу transformers, прогнать dev/test (`distinct`, Dialogs, DataHive, VoxConverse) | DER (25), перекрытия, тайминги | на англ. бенчмарках DER примерно в 1.5 раза ниже Sortformer v2.1; без порога, 1..8 спикеров | 3–4 (PC) | **высокий**: русский не заявлен | HF карточка; PR #4032 |
| 7 | Если №6 выиграл: **собрать AAR sherpa-onnx из PR #4119** (NDK r28c), оставить pyannote+CAM++ как fallback, замерить RTF и RAM на телефоне | DER, RTF | — | 8–12 | высокий: невлитый код, +103 МБ в APK, RTF на телефоне неизвестен | PR #4119; cassini native-runtime.md |
| 8 | **Спектральная кластеризация с eigengap (3D-Speaker / NME-SC)** по нашим эмбеддингам вместо порога; ограничение 1..8; на коротких файлах (< 30 с) — откат к AHC | DER | адаптивное число спикеров | 6–10 | средний | 3D-Speaker cluster.py; arXiv 2003.02405 |
| 9 | **Silero VAD v6.2** против текущего + подмешивание ESC-50 cat/dog/laughing в паузы dev | WER (пропуски на стилизованных голосах), правило 8 | `[Проверить]` | 2 | низкий–средний (v6 может чаще срабатывать на мяуканье) | silero-vad v6.2 release |
| 10 | **Измерить, нужна ли LLM для текста**, раз пунктуацию уже переносим из e2e: доля «неоформленных» реплик → если ≈0, отключить LLM для транскрипта (оставить для протокола) | RTF (10) | на ПК RTF 0.122 → 0.080 | 1 | низкий | TEST_RESULTS |
| 11 | `windowShiftRatio` 0.1 → 0.2/0.25 | RTF | примерно вдвое меньше эмбеддингов `[Проверить DER]` | 1 | низкий | sherpa PR #3870 |
| 12 | `modified_beam_search` (maxActivePaths 4) для RNNT без пунктуации | WER | `[Проверить]`; обычно небольшой | 1 | низкий (RTF↑) | sherpa nemo decoder |
| 13 | Нарезка ASR: хвост 0.5 с вместо паддинга 200 мс (вывод Cassini) | WER | `[Проверить]` | 1 | низкий | cassini pixel8-results |
| 14 | Перекрытия: в зоне двух спикеров слово отдавать «перебивающему», если его турн начинается в этой зоне; проверить на DataHive | тайминги, cpWER | `[Проверить]` | 2–3 | средний | DataHive метаданные |
| 15 | RUPunct_small → ONNX int8 вместо второго прохода e2e (только если e2e-проход дорог на телефоне) | RTF | минус целый проход энкодера 220M | 6–8 | средний (onnxruntime Java рядом с AAR sherpa) | HF RUPunct |
| 16 | Русский ITN → `ruleFsts` — **только если жюри пишет числа цифрами** | WER | зеркально №1 | 6–10 | средний | NeMo-text-processing; sherpa ITN |
| 17 | VoxLock для зон перекрытия + второй проход ASR | WER/cpWER на перекрытиях | `[Проверить]` | 12–20 | высокий | sherpa PR #4085 |
| — | Не делать: hotwords (правило 9), LM fusion (нет в Kotlin API), шумодав перед ASR (литература против), DiariZen/MSDD/reverb (лицензия или вес) | | | | | |

### Топ-5 быстрых побед на 1 день

1. **Вопрос организаторам о нормализации WER** (0.5 ч). До ответа оставить слова из RNNT без пунктуации.
2. **Продление границ реплик до турна/VAD** (2–3 ч) → DER-miss и тайминги.
3. **Новый dev для похожих и стилизованных голосов** из Langswap Dialogs, Piper ru и DataHive (3–4 ч), затем перепроверить порог и **A/B 4–5 эмбеддингов**, меняется только конфиг (2 ч).
4. **min_cluster_size + слияние центроидов + confidence** в `core` (3–5 ч) против лишних спикеров от мяуканья и смеха; проверить на ESC-50 cat.
5. **RTF-гигиена**: отключить LLM для транскрипта, если e2e-пунктуации хватает; `windowShiftRatio` 0.2; A/B Silero VAD v6.2 (вместе 3–4 ч).

Отдельная ставка на день 2: go/no-go Nemotron-3-Diarization на ПК (3–4 ч). Если на русском dev она даёт DER ниже pyannote+CAM++ в среднем и в худшем случае, собрать AAR из PR #4119.

---

## Источники
- Проект: `AGENTS.md`, `docs/TEST_RESULTS.md`, `docs/LIMITATIONS.md`, `docs/ADR/0001-models.md`
- DataHive: https://huggingface.co/datasets/datahiveai/russian-conversations-sample
- Langswap Dialogs: https://huggingface.co/datasets/langswap/dialogs-ru-emotional-conversations
- Synthetic diarization ru: https://huggingface.co/datasets/ivkond/synthetic-speech-diarization-ru
- Golos: https://github.com/salute-developers/golos/tree/master/golos ; https://huggingface.co/datasets/bond005/sberdevices_golos_10h_crowd
- SOVA RuDevices: https://huggingface.co/datasets/bond005/sova_rudevices
- Common Voice → MDC: https://huggingface.co/datasets/mozilla-foundation/common_voice_17_0 ; https://huggingface.co/spaces/mozilla-foundation/README
- FLEURS: https://huggingface.co/datasets/google/fleurs
- RuLS: https://www.openslr.org/96/ ; OpenSTT: https://github.com/snakers4/open_stt ; mTEDx: https://huggingface.co/datasets/deepdml/mtedx
- VoxConverse/AMI (diarizers): https://huggingface.co/datasets/diarizers-community/voxconverse , https://huggingface.co/datasets/diarizers-community/ami
- AliMeeting: https://www.openslr.org/119/ ; MSDWild: https://x-lance.github.io/MSDWILD ; M3SD: https://huggingface.co/datasets/Igor97/MISP-M3SD
- MUSAN: https://www.openslr.org/17/ ; RIRS_NOISES: https://www.openslr.org/28/ ; DEMAND: https://zenodo.org/records/1227121 ; ESC-50: https://github.com/karolpiczak/ESC-50
- HF datasets-server API: https://huggingface.co/docs/dataset-viewer
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx/releases (v2.0.1, v2.0.0, v1.13.8, v1.13.6); исходники `sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h`, `fast-clustering.cc`, `fast-clustering-config.h`, `offline-recognizer-transducer-nemo-impl.h`, `offline-transducer-modified-beam-search-nemo-decoder.h`, `kotlin-api/OfflineSpeakerDiarization.kt`, `kotlin-api/OfflineRecognizer.kt`; PR: https://github.com/k2-fsa/sherpa-onnx/pull/4119 , /pull/4032 , /pull/4085 (VoxLock), /pull/3881 (confidence), /pull/3870 (window shift)
- Модели sherpa: releases `speaker-recongition-models`, `speaker-segmentation-models`, `asr-models`, `tts-models`
- Nemotron-3-Diarization: https://huggingface.co/nvidia/Nemotron-3-Diarization ; ONNX: https://huggingface.co/NealCaren/Nemotron-3-Diarization-ONNX ; NeMo-Speech.cpp: https://github.com/NVIDIA/NeMo-Speech.cpp
- Streaming Sortformer v2: https://huggingface.co/nvidia/diar_streaming_sortformer_4spk-v2
- cassini-android: https://github.com/codemyriad/cassini-android (docs/notes/pixel8-results.md, docs/native-runtime.md)
- pyannote community-1: https://huggingface.co/pyannote/speaker-diarization-community-1 ; clustering: https://github.com/pyannote/pyannote-audio/blob/main/src/pyannote/audio/pipelines/clustering.py ; ONNX: https://huggingface.co/altunenes/speaker-diarization-community-1-onnx
- 3D-Speaker: https://github.com/modelscope/3D-Speaker/tree/main/egs/3dspeaker/speaker-diarization ; `speakerlab/process/cluster.py`
- NME-SC: https://arxiv.org/abs/2003.02405
- WhisperX: https://github.com/m-bain/whisperX/blob/main/whisperx/diarize.py
- GigaAM: https://github.com/salute-developers/GigaAM , https://github.com/salute-developers/GigaAM/blob/main/evaluation.md
- RUPunct: https://huggingface.co/RUPunct/RUPunct_small ; sbert_punc_case_ru: https://huggingface.co/kontur-ai/sbert_punc_case_ru ; punct_cap_seg_47: https://huggingface.co/1-800-BAD-CODE/punct_cap_seg_47_language ; silero-models: https://github.com/snakers4/silero-models
- NeMo text processing: https://github.com/NVIDIA/NeMo-text-processing ; num2words: https://github.com/savoirfairelinux/num2words
- Silero VAD: https://github.com/snakers4/silero-vad/releases
- Шумодав и ASR: https://arxiv.org/abs/2512.17562 ; https://arxiv.org/abs/2603.04710
- Transcribro: https://github.com/soupslurpr/Transcribro ; whisper-acft: https://github.com/futo-org/whisper-acft ; WhisperKitAndroid: https://github.com/argmaxinc/WhisperKitAndroid
