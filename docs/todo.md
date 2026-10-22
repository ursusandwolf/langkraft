# Langkraft Development Roadmap

## Актуальный план интеграции — 2026-10-21

Основа — dev `3003225`; работа ведётся в `integration/dev-base-20261021`. Отметки Done ниже относятся к исторической реализации и не заменяют проверку готовности к выпуску. Детали и риски: [план интеграции](INTEGRATION_PLAN_2026-10-21.md).

### Спринт INT-S1: восстановить тесты и JS-компиляцию dev

- [x] Изолировать SQLite backend-тестов, исправить временные данные cleanup и SQL-вставку теста синхронизации.
- [x] Заменить JVM-вызовы времени в PlayerViewModel на kotlinx.datetime.Clock.
- [x] Вынести IO dispatcher в платформенные реализации: JVM — IO, JS — Default.
- [x] Проверить `:shared:desktopTest :backend:test :shared:compileKotlinJs :webApp:compileKotlinJs`: 16 shared + 11 backend тестов прошли, обе JS-компиляции успешны.

### Спринт INT-S2: точечный перенос исправлений main

- [x] Проверять HTTP status, MIME, пустое тело и Content-Length до сохранения аудио; новые локальные файлы имеют расширение MP3.
- [x] Сохранять отмену корутин и завершать внешние процессы при отмене/таймауте, включая дочерние процессы.
- [x] Валидировать URL, ограничить очередь импорта, обрабатывать перегрузку и повреждённые субтитры, сохраняя jobId/polling dev.
- [x] Автотестами проверить HTTP-ошибки, отмену, переполнение очереди, продолжение работы воркера и сохранение реплик с одинаковыми временами в парсере. Итог INT-04: 28 shared + 26 backend тестов, JVM/JS-компиляция успешна.

Реальный YouTube/ffmpeg сценарий, сохранение связей словаря и регресс клиентов остаются в INT-S3/INT-S4. Завершение автотестов INT-S2 не означает готовность слияния.

### Спринт INT-S3: миграции и браузерное хранилище

- [x] Согласовать схемы обеих веток и миграции существующих БД на копиях данных (v3, backup, повторное открытие).
- [x] Исключить побочные удаления при повторном импорте; сохранить связи словаря, SRS и очередь sync.
- [x] Адаптировать Web SQLite/IndexedDB из main и подключить Web DI/плеер.
- [x] Проверить перезагрузку/offline и ошибки хранилища, а также smoke Desktop/TUI.

### Спринт INT-S4: регресс и слияние

- [x] Подготовить CI под итоговый стек, обновить инструкции запуска и выполнить локальный платформенный регресс; удалённый CI проверить перед слиянием.
- [x] Проверить авторизацию, sync двух клиентов, SRS, реальный импорт и воспроизведение.
- [ ] После проверок подготовить и слить PR integration → dev.
- [ ] После стабилизации слить dev → main с сохранением истории и ручной проверкой разрешённых конфликтов.

## Backlog после интеграции

- [ ] Общие квоты/retention медиа и очистка аварийных следов IndexedDB.
- [ ] Координация нескольких вкладок и профилей пользователей.
- [ ] Включение и регресс Android target.
- [ ] Проверка Gemini с реальным ключом и конкретных аудиоустройств/VLC в окружении пользователя.

## Phase 1: Foundation (Done)
- [x] Architecture design (KMP + Compose)
- [x] SQLDelight schema for Content and Vocabulary
- [x] Domain models and UseCases
- [x] Multiplatform Audio Player (expect/actual)
- [x] Basic Immersion Player UI

## Phase 2: Ingestion & Backend (Done)
- [x] Implement Ktor Backend using `YtdlpJava`
- [x] Robust SRT/VTT parsing logic
- [x] Audio streaming/downloading service (Opus format)
- [x] Error handling for YouTube extraction

## Phase 3: AI & Intelligence (Done)
- [x] Integrate Google Gemini API for translations (with `CachingLinguisticAssistant` Decorator)
- [x] **Deep Analysis Mode:** Grammatical breakdown of a specific subtitle line
- [x] **Contextual Lemmatization:** Link words to their base forms
- [x] **AI Correction:** Feature to correct user's "Active Writing"

## Phase 4: Learning Experience (Done)
- [x] **Spaced Repetition (SRS):** SM-2 algorithm integrated via Dependency Injection
- [x] **Repeat Loop Mode:** Easy toggle to loop a specific audio/subtitle segment
- [x] **Prose Memorization Tool:** Hidden-text mode
- [x] **Active Output:** Writing diaries
- [x] Progress tracking dashboard
- [x] **SRS UX Improvement:** Move from 0-5 scale to a 4-button Anki-style system (AGAIN, HARD, GOOD, EASY)
# Langkraft Development Roadmap

## Phase 5: UI/UX Polishing
- [x] Content selection screen (URL input, library)
- [x] Theming and design system (German aesthetics)
- [x] Speed control (0.75x, 1x, 1.25x)
- [x] Extract offline downloading logic from `PlayerViewModel` into a dedicated `OfflineDownloadDelegate`
- [x] TUI Player: Enable phrase segmentation and real-time active marker
- [x] TUI Player: Add navigation and playback controls
- [x] Implement Waveform amplitude extraction on backend to power `WaveformVisualizer` (currently simulated)
- [x] Refactor audio playback: Migrated from `ffplay` subprocess hack to `vlcj` for native seek/rate control
- [x] Implement automated cleanup for downloaded media files

- [x] Android APK/Bundle build configuration
- [x] Web production deployment setup (Compose HTML/Wasm)
- [x] User authentication (BCrypt hashing) and JWT routing
- [x] Change Ktor `/api/ingest` to be asynchronous (polling/webhooks) to avoid HTTP timeouts during long downloads

## Phase 7: Synchronization & Offline First (Done)
- [x] Backend incremental synchronization protocol (Optimized with Batch Upserts)
- [x] Implement database-backed Offline-First Queue (`PendingSyncChange`)
- [x] Refactor `SyncManager` to use `VocabularyRepository.sync()` instead of in-memory queue
- [x] Remove hardcoded sync URL from `SqlDelightVocabularyRepository`
- [x] Implement conflict resolution logic on backend (Last Write Wins based on `lastUpdated`)
- [x] Integrate `SyncManager` into `DashboardViewModel` to trigger sync on app start
