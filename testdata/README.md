# testdata — матрица условий для оценки

Только наши данные (AGENTS.md §2.6). Пороги подбираются на `dev/`, отчёт — на отложенном `test/`.
Критерий выбора конфигурации — лучшее **среднее** и лучший **худший** случай по всем ячейкам.

## Структура

```
testdata/
  dev/   <name>.<wav|mp3|m4a|ogg|opus|flac|amr>   аудио
         <name>.json                                эталон строго по контракту
         <name>.meta.json                           метаданные (необязательно, но нужно для ячеек)
  test/  то же; файлы не пересекаются с dev ни по дикторам, ни по исходным записям
```

Подпапки внутри `dev/`/`test/` допустимы (`report.py` ищет рекурсивно), имена `<name>` должны быть уникальны.

Эталон `<name>.json`: `{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"..."}]}`,
инварианты контракта проверяет `python tools/validate_json.py <name>.json --audio <name>.wav`.

`<name>.meta.json`:
```json
{"duration_ms": 61234, "source": "own|cv|fleurs|sova|openstt|golos|voxconverse|ami|synthetic",
 "license": "CC0-1.0",
 "conditions": {"speakers": 2, "duration": "<1m", "quality": "room", "voices": "mf",
                "dynamics": "interruptions", "format": "m4a_44k_stereo", "origin": "own"}}
```
`report.py` усредняет метрики по каждой паре «ось = значение» из `conditions`.

## Оси матрицы (каждое значение — минимум 3 файла в dev и в test)

| ось (`conditions`) | значения |
|---|---|
| `speakers` | 1, 2, 3, 5 |
| `duration` | `<1m`, `5m`, `30m`, `60m` |
| `quality` | `studio`, `room`, `phone8k`, `snr5`…`snr20` (шум/музыка), `quiet`, `clipped` |
| `voices` | `mm`, `ff`, `mf`, `similar`, `tts`, `stylized` |
| `dynamics` | `short_answers`, `interruptions`/`overlap`, `monologue`, `long_pauses` |
| `format` | wav/mp3/m4a/ogg/opus/flac/amr × 8–48 кГц × моно/стерео (например `mp3_44k_stereo`) |

Плюс «злые» файлы для red-team (0 речи, только музыка/животные, битый файл) — у них эталон `{"transcript":[]}`.

## Источники

| источник | для чего | лицензия |
|---|---|---|
| свои записи команды (разные телефоны, комнаты, дистанция) | все оси, особенно `quality`, `format`, `dynamics` | наши; согласие дикторов |
| Common Voice ru (`validated.tsv` + `clips/`) | ASR, синтетика с id диктора | CC0-1.0 |
| FLEURS ru_ru (google/fleurs) | ASR; синтетика только 1 реплика/спикер (нет id диктора) | CC BY 4.0 |
| SOVA Dataset | ASR, разные условия | CC BY 4.0 [Проверить версию] |
| OpenSTT (Russian Open STT) | ASR, телефон/YouTube | CC BY-NC 4.0 — только для оценки, не для обучения/распространения |
| Golos | ASR; **in-domain для GigaAM** — отчитывать отдельно | [Проверить лицензию] |
| VoxConverse, AMI | диаризация на чужом языке/условиях | CC BY 4.0 |

Корпуса в git не кладём (размер, лицензии): в репозиторий попадают только итоговые нарезки
небольшого размера с указанием источника и лицензии в `meta.json`.

## Синтетические диалоги

`tools/make_synthetic.py` склеивает реплики разных дикторов (паузы 0–1.5 с, перекрытия 0–20 %,
опционально шум с заданным SNR) и сразу пишет `wav` 16 кГц + эталон + `meta.json`:

```
python tools/make_synthetic.py <cv-corpus>/ru testdata/dev/syn --layout cv --n 5 --speakers 3 --duration-s 300 --snr-db 10
python tools/make_synthetic.py my_speakers/ testdata/test/syn --layout dirs --speakers 2 --seed 1
```
Дикторы dev и test не должны пересекаться (используйте разные подмножества источника).

## Прогон

```
python -m pip install -r tools/requirements.txt
python tools/report.py testdata/dev out/dev --timings out/dev/timings.json --md out/dev/report.md
```

## live_dev / live_test — живые, актёрские, Piper и «котики»

Отдельные от `dev/`/`test/` наборы (их не меняют). Сборка воспроизводима из `.cache/data/`:
`bash tools/build_live_sets.sh` (скачивание ~400 МБ в `.cache/data/<источник>/`, venv с piper-tts в
`.cache/venv_piper`, сборка, проверка контракта; `FETCH=0` — без сети). Подробности метода —
в docstring `tools/build_live_sets.py`. wav в git не коммитятся (`.gitignore`), эталоны и meta — да.

| префикс | источник | что проверяет | dev | test | текст |
|---|---|---|---|---|---|
| `dh_*` | DataHive Russian Conversations (sample), живые VoIP-разговоры знакомых людей, стерео (собеседник = канал) → сумма каналов, 16 кГц моно | реальная диаризация, перекрытия 4–16 %, короткие реплики, шум быта | 4 (2.5–4 мин; пары стран KZ, ME) | 4 (3–7 мин; RU ×2, UA, DK-SK) | **нет** (`has_text: false`) |
| `ls_*` | Langswap «Dialogs»: окна по 36 подряд идущих реплик сценарного диалога актёров, с максимумом шёпота/смеха/надменности/гнева | похожие голоса (Masha↔Sveta, `ff_stylized`), стилизованная речь (`mf_stylized` с Dima) | 3 (part9, part5, dima part9) | 3 (part3, part6, dima part11) | да |
| `piper_*` | Piper ru (irina Ж, ruslan/dmitri/denis М) читают `synth_lines_ru.txt` (чётные строки — dev, нечётные — test) | `voices=distinct_piper`: 4 разных синтетических голоса вместо сдвига высоты одного | 2 (2 и 3 спк) | 2 (2 и 3 спк) | да |
| `meow_*` | 3 диалога из `testdata/dev` + 18 клипов ESC-50 (cat 8, dog 5, laughing 5), вставлены в паузы между репликами (0.3 с тишины + клип ≤ 3 с на уровне речи −6…0 дБ + 0.3 с) | правило 2.8: неречь не даёт реплик и спикеров; эталон = исходный со сдвигом времени, позиции вставок в `meta.inserted` | 3 | — (только dev) | да |

**Эталон DataHive** (текста нет): по каждому каналу отдельно — энергия в полосе 100–1000 Гц (кадр 20 мс,
шаг 10 мс), гистерезис L95−30 / L95−36 дБ (L95 — 95-й перцентиль уровня канала), отрезок остаётся при
≥ 15 % периодичных кадров (автокорреляция > 0.6 на лагах F0 60–400 Гц), +50 мс по краям, паузы < 1 с
сливаются, реплики < 250 мс отбрасываются; спикер = канал. Утечки между каналами нет (корреляция ≈ 0).
Погрешность (сравнение с независимым Silero VAD по тем же каналам, `meta.ref_check`): DER «энергия против
Silero» на канал в среднем ≈ 10 %, максимум 24 % (collar 250 мс), медианная разница границ 16–51 мс; доля
речи второго собеседника совпадает с `speech_balance_percent` DataHive в пределах ±7 п.п. Поэтому DER на
`dh_*` ниже ~10 % не различим. Систематика: смех/кашель/дыхание у микрофона размечаются как речь, очень
тихие хвосты фраз теряются. `ru_000005` исключён (на одном канале детекторы расходятся на 60 %). В `dh_*`
dev и test разведены по паре стран (прокси диктора: id дикторов в данных нет, повторы дикторов возможны).

`report.py` для `has_text: false` (поле в meta или в `conditions`) считает DER, границы и число спикеров,
а WER/cpWER пропускает («—», не входят в среднее). В `ls_*` дикторы dev и test одни и те же (их всего 3),
различаются части сценария; соседние реплики одного актёра с паузой < 1 с слиты в одну реплику эталона.

| источник | лицензия | что делать нельзя |
|---|---|---|
| DataHive Russian Conversations sample | CC BY 4.0 (указывать DataHive) | — |
| Langswap Dialogs | OpenRAIL (`LICENSE.md` датасета; коммерция разрешена) | нарушать ограничения раздела 2; при распространении передавать лицензию; цитировать arXiv:2607.14310 |
| Piper ru (rhasspy/piper-voices), piper-tts 1.8.0 (GPL-3.0, только инструмент на ПК) | голоса: denis, dmitri — данные CC0; irina — лицензия данных не указана (RHVoice); ruslan — данные CC BY-NC-SA 4.0 | `piper_*` — только внутренние тесты, не распространять аудио |
| ESC-50 | CC BY-NC 3.0 (K. Piczak) | `meow_*` — только внутренние тесты |
