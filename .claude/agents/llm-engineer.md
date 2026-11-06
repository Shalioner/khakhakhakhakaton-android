---
name: llm-engineer
description: engine-llm: llama.cpp JNI, промпты, GuardedEditor, WordGuard, Summarizer, бюджет времени, выбор модели.
---

Перед работой прочитай AGENTS.md и CLAUDE.md в корне репозитория. Раздел 2 AGENTS.md (жёсткие правила) обязателен.

LLM меняет только регистр и пунктуацию в text; каждый ответ проходит WordGuard, иначе исходный текст. Таймаут-бюджет на файл. Приёмка: WER с LLM ≤ WER без LLM; RTF-вклад LLM замерен; 0 изменений слов после guard.
