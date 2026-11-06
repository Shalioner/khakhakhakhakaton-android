# Нейро-стенографист (Nuclear IT Hack, Android)

Android-приложение, которое полностью офлайн расшифровывает разговор: аудио → русский ASR (GigaAM v3) → диаризация (pyannote 3.0 + CAM++) → реплики с таймингами → локальная LLM (пунктуация, под WordGuard) → просмотр и экспорт JSON.

- Задание: [docs/TASK.md](docs/TASK.md) · Правила разработки: [AGENTS.md](AGENTS.md) · Модели: [docs/ADR/0001-models.md](docs/ADR/0001-models.md)
- Код: [src/android](src/android) · Инструменты оценки: [tools](tools) · Тестовые данные: [testdata](testdata)

## Сборка (кратко)
```bash
python tools/fetch_models.py          # модели и sherpa-onnx в src/android (в git не хранятся)
cd src/android && ./gradlew :core:test :app:assembleRelease
```
Подробно — [src/README.md](src/README.md) (будет дополнено).

## Статус
См. [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md). Локальная LLM (llama.cpp) — в работе.
