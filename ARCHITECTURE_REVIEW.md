# Кодрев’ю / архітектурний аналіз Langkraft

Дата: 2026-11-01 · Skill: code-reviewer · Роль: архітектор + аналітик · Git: sync `origin/main` @ `1bf1a09`

## Вердикт: ⚠️ мержити після правок (для нових змін); поточний main — прийнятний baseline

**Кратко:** KMP-моноліт з портами/адаптерами адекватний продукту (іммерсія + offline + sync). Інтеграційні й REL-S1…S7 спринти закриті; production ще не сертифікований (Android вимкнено, Gemini/real devices — окремо). Головний ризик — складність ownership аудіо/recovery і зв’язність `shared` (UI+domain у одному модулі для backend).

### Стадія спринтів

| Блок | Стан |
| --- | --- |
| Foundation / Ingestion / AI / SRS / UI | Done |
| INT-S1…S4 (злиття main↔dev) | Done |
| REL-S1…S7 | Done (включно budget, retention, recovery limits, remote AI, LK-04) |
| Backlog | streaming recovery; client media quotas; account/profile policy; Android; live Gemini |

### Адекватність архітектури

| Критерій | Оцінка |
| --- | --- |
| Модульний моноліт KMP + окремий Ktor backend | ✅ відповідає команді/продукту |
| Порти (repository/ai/audio/player) | ✅ правильні межі |
| `backend → shared` (моделі+контракти, але тягне Compose-залежності shared) | 🟠 Major: надмірна зв’язність модуля |
| Lease/recovery протоколи | ✅ виправдана складність для offline |
| Android у репо, але вимкнений | 🟡 Minor: дрімаючий код — ок до окремого спринту |

Не потрібні мікросервіси. Окремий `:domain` Gradle-модуль — наступний крок, не блокер поточної функціональності.

### Патерни

Див. таблицю в `AI_MAP.md`. Коротко: Ports/Adapters, Repository, Strategy (SRS/AI), Decorator (cache/journal), Adapter (HTTP/process/SQL WASM), MVVM+Delegate, Observer (Flow/BroadcastChannel), Producer–Consumer (ingestion queue), expect/actual.

### 🔴 Blocker

Немає підтверджених блокерів у зібраному після sync коді (без профілю повного Web CI на цій машині).

### 🟠 Major: backend залежить від «товстого» shared

**Де:** `settings.gradle.kts`, `backend/build.gradle.kts` → `:shared`  
**Проблема:** shared містить UI Compose; backend отримує зайвий граф залежностей. Ускладнює оновлення і межі шарів.  
**Пропозиція:** окремий спринт `:domain` (моделі + порти + SRS + AI contracts) без Compose; shared/ui і backend залежать від нього.

### 🟠 Major: критична логіка потребує JVM+Web регресії при змінах

**Де:** `AudioDownloaderImpl`, `*Recovery`, `BackendMediaStorage`, js `storage/`  
**Проблема:** зміни без повного контуру тестів легко ламають offline/leases.  
**Пропозиція:** будь-який PR у ці зони — обов’язково `desktopTest` + відповідні storage/recovery тести.

### 🟡 Minor: дрімаючий Android

**Де:** `androidApp/`, `shared/src/androidMain` (androidTarget закоментований)  
**Проблема:** код не в активній збірці; може роз’їхатися з common.  
**Пропозиція:** тримати як backlog; не «чистити» без Android sprint.

### 🟡 Minor: застарілі Material Icons

**Де:** `DashboardView`, `WritingView` (deprecated Icons)  
**Пропозиція:** AutoMirrored icons у косметичному PR.

### 💡 Idea

- Прибрати невикористану `targetOs` у `shared/build.gradle.kts`.
- OptIn `ExperimentalCoroutinesApi` у VM-тестах.

### 👍 Удачно

- Розділення LocalContent / Remote / AudioDownloader.
- DatabaseAccess як єдина серіалізація доступу до SQLDelight.
- CachingLinguisticAssistant + RemoteLinguisticAssistant (ключ Gemini лише на сервері).
- ADR на retention/budget/recovery — рішення зафіксовані.

### Зроблені безпечні правки цього рев’ю

1. `UnauthorizedException` об’єднано в `Exceptions.kt` (ієрархія `LangkraftException`).
2. Прибрано мертвий коментар DI і застарілий коментар про ContentRepository.
3. Додано `AI_MAP.md`, `LEARNING_GUIDE.md`, цей звіт.

Методи з майже однаковим тілом (`asFlowList`/`asFlowOne`, Logger.d/i/w) **не зливались** — різні контракти API.

## Локальний прогін тестів (2026-11-01)

| Набір | Результат |
| --- | --- |
| `:shared:desktopTest` | 98 tests, 0 fail |
| `:backend:test` | 68 tests, 0 fail, 1 skipped |
| Разом | **166**, BUILD SUCCESSFUL |
| Не запускалось тут | JS/Chromium, web-storage Playwright, live YouTube/Gemini |

Лог: `TEST_RESULTS.txt`. Підсумок: `REVIEW_SUMMARY.txt`.

## Чого не вистачає по тестах (сценарії)

- Live Gemini з ключем (manual).
- Android assemble (коли увімкнуть target).
- Streaming Web recovery великих бібліотек (backlog).
- Client-side media quota/retention.

## Псевдографічний UML (класи / інтерфейси)

```
                    <<ui>>
        +-------------------------------+
        |         MainApp / Screen      |
        +---------------+---------------+
                        |
        +---------------v---------------+
        |     *ViewModel : StateVM      |
        |  PlayerVM *-- LinguisticDel   |
        |           *-- OfflineDel      |
        +-------+-------------+---------+
                |             |
     <<domain ports>>         |  <<domain>>
+-----------v-----------+     |  IngestContentUseCase
| LocalContentRepository|     |  SpacedRepetitionAlgorithm
| RemoteContentSource   |     |       ^
| AudioDownloader       |     |       | Sm2Algorithm
| VocabularyRepository  |     |
| LinguisticAssistant   |<----+---- RemoteLinguisticAssistant (client)
+-----------+-----------+             MockLinguisticAssistant
            |                         Gemini + Caching (backend)
   <<data adapters>>
+-----------v-------------------------------+
| SqlDelightContentRepository               |
| SqlDelightVocabularyRepository            |
| BackendRemoteSource  --> HTTP /api        |
| AudioDownloaderImpl --> AudioStorage      |
|                     --> AudioDownloadCoord|
| SyncManager / UserSession                 |
| DatabaseHandle *-- DatabaseAccess *-- DB  |
+-------------------------------------------+
            |
            |  HTTP
            v
+-------------------------------------------+
|                 <<backend>>               |
| Routing --> AuthService / SyncService     |
|         --> YouTubeIngestionService       |
|               |-- YtdlpClient             |
|               |-- ProcessRunner           |
|               |-- SrtParser / Waveform    |
|               |-- BackendMediaStorage     |
|         --> LinguisticAssistant (cache)   |
| db: Users / IngestionJobs / VocabularySync|
+-------------------------------------------+

Audio ownership (спрощено):
  AudioStorage --store--> location
       | lease (Web Lock / FileLock)
  AudioDownloaderImpl --SQL link--> Content.localAudioPath
       | cancel/compensation: delete only unreferenced NEW object
  Pending/AudioDownloadRecovery --startup--> ERROR|COMPLETED|confirm|purge
```

Повний Mermaid: `docs/UML_CLASSES.md`.
