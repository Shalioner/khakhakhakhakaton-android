# CLAUDE.md
Главные правила проекта — в [AGENTS.md](AGENTS.md). Прочитай его перед любой задачей.

Адаптация под шаблон организаторов (github.com/grigorevmp/hacathon_template):
- Gradle-проект Android лежит в `src/android/` (модули `app`, `core`, `engine-audio`, `engine-sherpa`, `engine-llm`, `bench`).
- `tools/`, `testdata/` — в корне репозитория.
- Документация — `docs/`, результаты тестов — `artifacts/test-results/`.
- Модели НЕ коммитятся в git: `tools/fetch_models.py` кладёт их в `src/android/app/src/main/assets/models/`.
- Разработка на Windows: JDK 21 `C:\Program Files\Android\openjdk\jdk-21.0.8`, SDK `C:\Users\leo\Android\Sdk`.
- Роли агентов — `.claude/agents/*.md` (lead, researcher, android-integrator, speech-engineer, llm-engineer, eval-engineer, ui-engineer, reviewer, red-team, docs-release).
