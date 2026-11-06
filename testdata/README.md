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
