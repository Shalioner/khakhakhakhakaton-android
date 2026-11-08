# Артефакты тестирования

Методика и выводы — [docs/TEST_RESULTS.md](../../docs/TEST_RESULTS.md).

| файл | содержимое |
|---|---|
| `results.csv` | по строке на тестовый файл (dev + test): условия, WER, cpWER, DER, ошибка границ, RTF, проверка контракта |
| `dev_report.md` / `dev_report.csv` | отчёт `tools/report.py` по `testdata/dev` (подбор параметров) |
| `test_report.md` / `test_report.csv` | отчёт по отложенному `testdata/test` (контроль) |

Метрики в CSV — доли (0.009 = 0.9 %), в `.md` — проценты. RTF измерен на ПК (bench), **не на телефоне**; данные синтетические (Windows TTS). ПК bench использует pyannote + CAM++ и не поддерживает Nemotron, включённый в Android APK. Эти файлы — исторические результаты; проверки нового Mac APK v0.2.0 публикуются отдельно в [отчёте выпуска](../../docs/RELEASE-0.2.0.md).

Воспроизвести:
```bash
bash tools/build_synth_matrix.sh
(cd src/android && ./gradlew :bench:run --args="testdata/test out/test_words --llm none")
python tools/report.py testdata/test out/test_words --timings out/test_words/timings.json --md artifacts/test-results/test_report.md
```
`--llm none` обходит известную неисправность текущей Windows `neurollm.dll`; это новый прогон без LLM, а не обещание побитового воспроизведения исторических CSV. Команды выполняются из корня репозитория.
