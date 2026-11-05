# Human review packet — Langkraft

Snapshot: [ursusandwolf/langkraft](https://github.com/ursusandwolf/langkraft) · branch `main` · SHA [`91038b5`](https://github.com/ursusandwolf/langkraft/commit/91038b5) · date 2026-11-05  
Review goal: **готовність baseline до ручного рев’ю / наступного спринту** (не production certification)  
Primary requirements: [README](../README.md), [docs/todo.md](todo.md), [docs/PROJECT_CONTEXT.md](PROJECT_CONTEXT.md), ADR у `docs/`  
Default branch versus this snapshot: рев’юер бачить **той самий** `origin/main` @ `91038b5` (немає відкритого unmerged PR для цього пакету)

Skill: глобально встановлено `~/.cursor/skills/human-code-review-handoff/` (з архіву `human-code-review-handoff-cursor.zip`).

## Start here

1. [AI_MAP.md](../AI_MAP.md) — полиці модулів і правила змін.  
2. [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) — сценарії імпорту / offline / sync / AI.  
3. [UML_CLASSES.md](UML_CLASSES.md) — Mermaid класів.  
4. Цей пакет — карти вимог → код і докази перевірок.  
5. Запуск без секретів у git: [RUNNING.md](RUNNING.md) (`LANGKRAFT_JWT_SECRET` локально ≥32 символи; `GEMINI_API_KEY` опційно).

**Обов’язковий scope (реалізовано в main):** KMP-клієнти Desktop/TUI/Web, Ktor backend, YouTube ingest (job polling), offline audio + recovery протоколи, SRS SM-2, JWT auth + vocabulary sync, remote AI через backend.  
**Опційно / backlog:** streaming Web recovery, квоти клієнтських медіа, політика профілів/вкладок, Android target, live Gemini/VLC у середовищі користувача.  
**Не плутати:** історичні «Phase Done» і зелений JVM CI ≠ production ready ([README](../README.md)).

## Requirement and model map

| Source requirement / model | Actual package and class | Important differences | Evidence / remaining gap |
|---|---|---|---|
| Immersion content + cues + waveform | [`ImmersionContent`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/model/Models.kt), `SubtitleLine` | Playback URL винесено в shared extension (не Ktor у domain) | [`ImmersionPlayback.kt`](https://github.com/ursusandwolf/langkraft/blob/91038b5/shared/src/commonMain/kotlin/com/langkraft/data/ImmersionPlayback.kt) |
| Vocabulary + SRS fields | [`VocabularyWord`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/model/Models.kt), `ReviewQuality`, `WordStatus` | Anki-style 4 кнопки → SM-2 quality | [`Sm2Algorithm`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/srs/SrsEngine.kt) |
| Auth / sync DTO | [`AuthModels.kt`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/model/AuthModels.kt) | Sync лише словника, не медіа-бібліотеки | ADR/DEVELOPER_GUIDE |
| Ingest job | [`IngestionJob`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/model/Models.kt) | Accept POST ≠ cancel on disconnect | PROJECT_CONTEXT |
| Linguistic AI | [`LinguisticAssistant`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/ai/LinguisticAssistant.kt) | Client = Remote; Server = Gemini\|Mock + Cache | Ключ лише на backend |
| Client ports | [`Repositories.kt`](https://github.com/ursusandwolf/langkraft/blob/91038b5/domain/src/commonMain/kotlin/com/langkraft/domain/repository/Repositories.kt) | Реалізації в `shared/.../data` | — |
| SQLDelight schema v3 | `shared/.../sqldelight` | Міграції з історичних гілок | desktopTest міграцій |
| Offline ownership LK-02/03 | `AudioDownloaderImpl`, `*Recovery`, platform `AudioStorage` | Три власники: storage→downloader→SQL | [ADR_AUDIO_DOWNLOAD_RECOVERY.md](ADR_AUDIO_DOWNLOAD_RECOVERY.md) |

## Entrypoints

| Required operation | Actual entry | Method / path / command | Contract notes |
|---|---|---|---|
| Register / login | [`Routing.kt` authRoutes](https://github.com/ursusandwolf/langkraft/blob/91038b5/backend/src/main/kotlin/com/langkraft/backend/Routing.kt) | `POST /api/auth/register`, `POST /api/auth/login` | BCrypt + JWT; IO dispatcher |
| Start ingest | same + [`YouTubeIngestionService`](https://github.com/ursusandwolf/langkraft/blob/91038b5/backend/src/main/kotlin/com/langkraft/backend/YouTubeIngestionService.kt) | `POST /api/ingest` → jobId | 503 якщо черга повна; disconnect не скасовує accepted job |
| Poll ingest | Routing | `GET /api/ingest/{jobId}` | Client poll timeout 900s |
| Media stream | [`MediaRoutes.kt`](https://github.com/ursusandwolf/langkraft/blob/91038b5/backend/src/main/kotlin/com/langkraft/backend/MediaRoutes.kt) | GET/HEAD/Range `/api/media/...` | Lease + optional retention/budget |
| Vocabulary sync | Routing (JWT) | `POST /api/sync` | Cursor + pending ack |
| AI ops (JWT) | Routing | `POST /api/ai/translate-word\|translate-sentence\|analyze-sentence\|correct-text` | Без ключа → mock на сервері |
| Client UI | [`MainApp.kt`](https://github.com/ursusandwolf/langkraft/blob/91038b5/shared/src/commonMain/kotlin/com/langkraft/MainApp.kt) | Compose screens + Koin | Web/Desktop/TUI |
| Web bootstrap | [`webApp/.../Main.kt`](https://github.com/ursusandwolf/langkraft/blob/91038b5/webApp/src/jsMain/kotlin/Main.kt) | browser | proxy `/api` |
| Desktop / TUI | `shared/desktopMain` `main.kt` / `CliMain.kt` | `gradlew :shared:run` / `runTui` | `--smoke` без вікон |
| Web recovery UI | `recovery.html` + js storage | `/recovery.html` | окремо від Compose; ліміт архіву 64 МіБ |

## Main flow and diagram

**Імпорт → локальна бібліотека (representative):**  
`ContentSelectionViewModel` → `IngestContentUseCase` → `BackendRemoteSource` (`POST /api/ingest`, poll) → `YouTubeIngestionService` / `YtdlpClient` / `SrtParser` → job READY → `SqlDelightContentRepository.saveContent`. Offline MP3 окремо: `OfflineDownloadDelegate` → `AudioDownloaderImpl` → `AudioStorage` + lease → SQL link.

```
:webApp / Desktop / TUI
        |  Koin
        v
   :shared (UI + data adapters)
        |  api
        v
   :domain (models, ports, SRS, AI contracts, use cases)
        ^
        |  implementation (prod)
   :backend (Ktor, Exposed, yt-dlp, Gemini)
        |
        +--> downloads/ + SQLite data/
```

Опущено: повний Web Lock / BroadcastChannel граф — див. UML_CLASSES / CHANGELOG_UML.

## Findings

| ID / priority / status | Location and trigger | Impact and evidence | Fix / decision |
|---|---|---|---|
| F1 Fixed | Infinite ingest poll | зависання корутини | `BackendRemoteSource` timeout 900s + тест @ `e6ac19d` |
| F2 Fixed | Sync throttle before lock | пропуск sync на 60с | `SyncManager.tryLock` @ `e6ac19d` |
| F3 Fixed | Sync без HTTP status check | parse error замість 401 | VocabularyRepository @ `e6ac19d` |
| F4 Fixed | Gemini HttpClient без timeout | зависання worker | BackendModule @ `e6ac19d` |
| F5 Fixed | Auth/sync/ingest на event loop | блокування Netty | IO wrappers @ `e6ac19d`/`abe9416` |
| F6 Fixed | SRS advance до save | втрата прогресу | SrsTrainingViewModel @ `e6ac19d` |
| F7 Fixed | ERROR job лишав contentJson | stale content при poll | IngestionJobRepository @ `abe9416` |
| F8 Fixed | lease claim після failed close | wedge до рестарту JVM | FileAudioDownloadCoordinator @ `abe9416` |
| F9 Fixed | backend→Compose shared | зайві залежності | `:domain` @ `65334a2` |
| F10 Open policy | Streaming Web recovery | великі бібліотеки | backlog sprint |
| F11 Open policy | Client media quotas / IDB cleanup | диск / сміття | backlog |
| F12 Open policy | Account/profile multi-tab beyond LK-04 | координація | backlog |
| F13 Open | Android target off | немає APK регресу | окремий спринт |
| F14 Open / manual | Live Gemini + VLC/ffplay | не сертифіковано тут | середовище користувача |
| F15 Doc gap | README «стан на 22 жовтня» | SHA/архитектура вже новіші | оновити дату/стан (опційно) |

## Verification

| Command / CI | Exact SHA and environment | Result | Limits / skips / fakes |
|---|---|---|---|
| `:domain:jvmTest :shared:desktopTest :backend:test` | `91038b5`, JDK Corretto 21.0.12.1, Windows | BUILD SUCCESSFUL; domain 8 / shared desktop 91 / backend 69 (1 skipped) | Web/Chromium Playwright **не** переганялись у цій сесії handoff |
| Prior focused suites | `e6ac19d` / `abe9416` | Sync/SRS/Poll/Auth/Coordinator/Repo OK | — |
| CI workflow | [`.github/workflows/ci.yml`](https://github.com/ursusandwolf/langkraft/blob/91038b5/.github/workflows/ci.yml) | включає `:domain:jvmTest` + desktop + backend; JS job окремо | Старий зелений CI на іншому SHA — історичний |
| Live YouTube / Gemini / VLC | — | не запускалось у handoff | потребує інструментів і ключів користувача |

**Implemented:** core product + REL-S1…S7 + `:domain` на `main`.  
**Tested (this machine):** JVM domain/shared/backend як вище.  
**Integrated/released:** код на `origin/main` @ `91038b5`; **не** заява production release.  
**Remaining:** F10–F14; опційно F15.

## Questions for the human

1. **Мета цього рев’ю:** acceptance baseline для наступного спринту, чи претензія на release readiness? (від цього залежить глибина Web/Chromium і live Gemini перевірок.)  
2. **Наступний спринт:** потоковий Web recovery **чи** квоти клієнтських медіа — що пріоритетніше?  
3. Чи оновлювати публічний README «стан проекту» під `91038b5` / `:domain` у рамках цього рев’ю?

**Очікуваний outcome рев’ю:** підтвердити/відхилити baseline `main@91038b5` як точку старту наступної роботи; зафіксувати пріоритет backlog без зміни lease/recovery семантики без регресійних тестів.

