# Артефакты тестирования

Методика и выводы — [docs/TEST_RESULTS.md](../../docs/TEST_RESULTS.md).

| файл | содержимое |
|---|---|
| `results.csv` | по строке на тестовый файл (dev + test): условия, WER, cpWER, DER, ошибка границ, RTF, проверка контракта |
| `dev_report.md` / `dev_report.csv` | отчёт `tools/report.py` по `testdata/dev` (подбор параметров) |
| `test_report.md` / `test_report.csv` | отчёт по отложенному `testdata/test` (контроль) |

Метрики в CSV — доли (0.009 = 0.9 %), в `.md` — проценты. RTF измерен на ПК (bench), **не на телефоне**; данные синтетические (Windows TTS).

Воспроизвести:
```bash
bash tools/build_synth_matrix.sh
cd src/android && ./gradlew :bench:run --args="testdata/test out/test_words"   # по умолчанию — тот же конвейер, что в APK
python tools/report.py testdata/test out/test_words --timings out/test_words/timings.json --md artifacts/test-results/test_report.md
```
