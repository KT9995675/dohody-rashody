# Версии проекта «Доходы–расходы»

Текущая стабильная версия: **1.1.0** (`versionCode` 2 в Android).

| Версия | Дата | Метка отката | Состав |
|--------|------|--------------|--------|
| **1.1.0** | 2026-09-02 | `v1.1.0` | Один экран Android: список + composer (текст / диктовка); категории в Настройках; фильтр с периодом; Mobile API v13; веб на ПК |
| 1.0.0 | 2026-08-29 | — | Первый рабочий APK (голос), GAS + Gemini, веб-UI |

## Как откатиться к 1.1.0

### Код (git)

После первого коммита в репозитории:

```bash
git tag -a v1.1.0 -m "Android 1.1.0: laconic UI, settings categories, date range"
git checkout v1.1.0          # только посмотреть
git checkout -b restore-1.1.0 v1.1.0   # ветка от этой версии
```

### Apps Script (сервер)

В редакторе: **Deploy → Manage deployments** → выбрать деплой **Android list+CRUD** → **Edit** → Version: **@13** (или версия, указанная в SPEC §0 на момент релиза).

### Android (APK)

Собрать из checkout `v1.1.0` или переустановить сохранённый файл:

`android/app/build/outputs/apk/debug/app-debug.apk` с `versionName` **1.1.0**.

## Что менять при следующем релизе

1. `VERSION.md` — новая строка в таблице  
2. `SPEC.md` §0 — поле «Версия»  
3. `android/app/build.gradle.kts` — `versionCode` (+1), `versionName`  
4. Git tag `vX.Y.Z` после коммита  
5. GAS: `clasp version "…"` + обновить deployment  
