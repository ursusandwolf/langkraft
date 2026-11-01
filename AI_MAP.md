# AI_MAP — карта для ШІ та підтримки Langkraft

Оновлено: 2026-11-01 · база `main` / `1bf1a09` (+ локальні дрібні правки рев’ю).

## Призначення

Цей файл — точка входу для агентів і людей: куди дивитися, що не чіпати без потреби, як безпечно оновлювати код.

## Стадія спринту

| Етап | Статус |
| --- | --- |
| Phase 1–5, INT-S1…S4 | Done |
| REL-S1…S7 (offline audio, LK-04, remote AI, retention, budget, recovery limits) | Done → merged `dev`/`main` |
| Відкритий backlog | Потоковий Web recovery великих бібліотек; квоти клієнтських копій; політика профілів/вкладок; Android target; Gemini з реальним ключем |

Джерело правди: `docs/todo.md`, `docs/PROJECT_CONTEXT.md`, `README.md`. Історичні «Phase Done» ≠ production certification.

## Модулі (Gradle)

```
langkraft
├── :shared     # domain + data + UI Compose + Desktop/TUI/JS adapters
├── :backend    # Ktor API, Exposed SQLite, yt-dlp/ffmpeg, Gemini
└── :webApp     # browser bootstrap + webpack; UI з :shared
```

`androidApp/` і `shared/src/androidMain` — **дрімаючі** (androidTarget вимкнено, модуль не в `settings.gradle.kts`). Не видаляти без окремого рішення по Android sprint.

## Шари shared (залежності лише вниз)

```
ui  →  domain (ports/usecase/srs/ai)  ←  data (adapters)
                 ↑
              di/Koin
```

| Полиця | Шлях | Що міняти тут |
| --- | --- | --- |
| Моделі | `shared/.../domain/model` | DTO/домен; serialization |
| Порти | `domain/repository`, `domain/ai` | інтерфейси без HTTP/SQL |
| Use case / SRS | `domain/usecase`, `domain/srs` | бізнес без UI |
| SQLDelight | `shared/.../sqldelight` | схема/міграції; не правити generated |
| Репозиторії | `data/repository` | SQLDelight/HTTP реалізації |
| Аудіо offline | `data/audio` | storage, lease, recovery |
| БД доступ | `data/database` | DatabaseAccess/Handle/Migration |
| Sync/auth client | `data/sync` | UserSession, SyncManager |
| UI | `ui/*` | ViewModel + Compose; делегати player |
| DI | `di/Koin.kt` + platform `*Module` | wiring |
| Backend | `backend/...` | routes, jobs, media, AI |
| Web storage JS | `shared/src/jsMain/storage` | IndexedDB/worker/recovery |

## Патерни (вже в коді)

| Патерн | Де | Навіщо |
| --- | --- | --- |
| Ports & Adapters | repository/ai/audio/player interfaces | підміна платформ і тестів |
| Repository | SqlDelight*Repository | ізоляція SQLDelight |
| Strategy | SpacedRepetitionAlgorithm, LinguisticAssistant | SM-2 / Gemini / mock |
| Decorator | CachingLinguisticAssistant, JournaledFileAudioStorage | cache / pending journal |
| Adapter | BackendRemoteSource, ProcessRunner, BrowserSqlDriver | HTTP/процеси/WASM SQL |
| MVVM + Delegate | *ViewModel, Player*Delegate | UI state + розбиття відповідальності |
| Observer | StateFlow, SQLDelight Query.Listener + BroadcastChannel | реактивність / LK-04 |
| Producer–Consumer | YouTubeIngestionService + Channel | черга імпортів |
| expect/actual | AudioPlayer, FileSystem, dispatchers | KMP платформи |

Не додавати новий GoF «на виріст». Спочатку біль → патерн.

## Гарячі зони (обережно)

1. **Offline audio ownership** — `AudioDownloaderImpl`, `AudioDownloadRecovery`, `PendingAudioRecovery`, platform storage. Три власники: storage → downloader → SQL. Див. `docs/ADR_AUDIO_DOWNLOAD_RECOVERY.md`.
2. **Backend media leases/budget** — `BackendMediaStorage`, `MediaLimits`. Див. ADR retention/budget.
3. **Web multi-tab** — `BrowserSqlDriver` + `database-events.js`. Не робити глобальну чергу транзакцій.
4. **Міграції схеми v3** — `DatabaseMigration` + `.sqm`. Тестувати на копіях даних.
5. **Ingestion processes** — `ProcessRunner` / yt-dlp tree kill при cancel/shutdown.

## Правила легкого оновлення

1. Змінюй один шар за раз; контракт порту спочатку, адаптер потім.
2. Новий клієнтський сценарій: ViewModel → порт → реалізація → тест → DI.
3. Backend endpoint: Routing → service → repository; винятки через `LangkraftException` / StatusPages.
4. Не чіпай recovery/lease протоколи без регресійних тестів зі списку desktopTest/js storage.
5. Документи: короткий changelog у `docs/CHANGELOG.md`; UML-зсув у `docs/CHANGELOG_UML.md` якщо змінюються зв’язки класів.
6. Реліз: sprint-гілка від sync `dev` → PR → CI → merge; `dev→main` окремим release PR.

## Тести — мінімальний контур

```powershell
$env:JAVA_HOME = 'C:\Users\Admin\.jdks\corretto-21.0.12.1'  # або свій JDK 21
.\gradlew.bat :shared:desktopTest :backend:test
```

Web/Chromium: `:shared:jsBrowserTest` + `tools/web-storage-tests` (потрібні Node/Playwright).

## Документи поруч

| Файл | Зміст |
| --- | --- |
| `ARCHITECTURE_REVIEW.md` | вердикт рев’ю + UML ASCII |
| `LEARNING_GUIDE.md` | навчальний розбір коду |
| `docs/DEVELOPER_GUIDE.md` | як влаштований runtime |
| `docs/UML_CLASSES.md` | Mermaid class diagrams |
| `TEST_RESULTS.txt` | останній локальний прогін |

## Чого ШІ не робити без явного ТЗ

- Увімкнення Android target / rewrite DI
- Видалення `androidMain` / legacy recovery
- Зміна семантики lease/pending/tombstone
- Великий рефактор shared→окремий domain-модуль (бажано, але окремий спринт)
