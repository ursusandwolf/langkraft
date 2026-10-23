# Langkraft Development Roadmap

## Текущий приоритет — MVP, 2026-10-23

Актуальная цель и критерии первой версии: [MVP](MVP.md). Ближайшие задачи — сохранение результатов SRS при ошибках (MVP-01), полный учебный путь (MVP-02), короткий запуск и закрытая проверка (MVP-03). Пункты backlog ниже не выполняются автоматически по порядку; потоковое recovery и расширение инфраструктуры отложены до подтверждённой потребности.

## Актуальный план интеграции — 2026-10-21

Основа — dev `3003225`; интеграция выполнена через `integration/dev-base-20261021`, PR #1 и PR #2 слиты. Четыре интеграционных спринта завершены; [итоговые проверки](INTEGRATION_COMPLETION.md) подтверждены локально и CI. Исторические Phase/Done ниже не заменяют проверку production-готовности. Детали решений: [план интеграции](INTEGRATION_PLAN_2026-10-21.md).

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

Реальный YouTube/ffmpeg сценарий, сохранение связей словаря и регресс клиентов выполнены в INT-S3/INT-S4. Границы внешних проверок перечислены в backlog.

### Спринт INT-S3: миграции и браузерное хранилище

- [x] Согласовать схемы обеих веток и миграции существующих БД на копиях данных (v3, backup, повторное открытие).
- [x] Исключить побочные удаления при повторном импорте; сохранить связи словаря, SRS и очередь sync.
- [x] Адаптировать Web SQLite/IndexedDB из main и подключить Web DI/плеер.
- [x] Проверить перезагрузку/offline и ошибки хранилища, а также smoke Desktop/TUI.

### Спринт INT-S4: регресс и слияние

- [x] Подготовить CI под итоговый стек, обновить инструкции запуска и выполнить локальный платформенный регресс; удалённый CI проверить перед слиянием.
- [x] Проверить авторизацию, sync двух клиентов, SRS, реальный импорт и воспроизведение.
- [x] После проверок подготовить и слить PR integration → dev: [#1](https://github.com/ursusandwolf/langkraft/pull/1), `8675897`.
- [x] После стабилизации слить dev → main с сохранением истории и ручной проверкой конфликтов: [#2](https://github.com/ursusandwolf/langkraft/pull/2), `9576ab4`.

## После интеграции: REL-S1 — offline-аудио и документация

- [x] Воспроизвести и исправить LK-02 на границе storage → downloader → SQL: отмена после публикации и COMMIT, потеря прежнего пути, удаление Content во время скачивания.
- [x] Добавить семь регрессионных тестов, проверку ошибок компенсации и чужих SQL-ссылок. Полный JVM-прогон: 90 тестов; Kotlin/Chromium: 26.
- [x] Актуализировать README, пошаговый запуск, карту классов UML и руководство разработчика с паттернами/модулями.
- [x] Перенести долговечный pending/recovery нового Web-протокола в REL-S2: incomplete, SQL DOWNLOADING и опубликованные непривязанные объекты. Legacy/no-lock и Desktop/TUI остаются отдельным продолжением.

## REL-S2: восстановление offline-загрузок

Спринт завершён и слит в dev через [PR #5](https://github.com/ursusandwolf/langkraft/pull/5), merge `384a30f`. Девять коммитов сохранены; JVM/Web CI успешны. Ветка REL-S2 удалена после проверки включения истории. База спринта — dev/main `b44ab98`.

- [x] Воспроизвести закрытие вкладки без abort: manifest и chunks остаются в IndexedDB.
- [x] Удерживать отдельный Web Lock до commit/abort; проверять владельца при append/commit/abort незавершённой загрузки.
- [x] При запуске Web БД и перед новой загрузкой атомарно удалять незавершённые объекты нового формата, только если их lock свободен. Повторная уборка безопасна.
- [x] Проверить закрытого/активного владельца в двух вкладках, готовое аудио, чужую запись, старый формат и работу без Web Locks в настоящем Chromium.
- [x] Проверить Kotlin/JS, development/production Webpack и полный UI-регресс обеих сборок с уборкой при перезапуске и сохранностью offline-воспроизведения.
- [x] Восстанавливать SQL DOWNLOADING нового Web-протокола при свободном content lock: ERROR без старого пути, COMPLETED с прежним audio. Защитить весь download lease, сохранять/удалять протокольную метку транзакционно.
- [x] Проверить сбой SQL, отмену, активную/закрытую вторую вкладку, legacy/no-lock и реальную блокировку во время HTTP; добавить регрессионные тесты downloader/recovery.
- [x] Сверять готовые pending-объекты нового Web-протокола: свободный content lease + ссылки всех Content; подтвердить связанные, атомарно удалить непривязанные manifest/chunks.
- [x] Подтверждать публикацию после SQL link; проверить отказ подтверждения, SQL/IDB ошибки, отмену, активного владельца, чужую ссылку, legacy и повторную сверку.
- [x] Desktop/TUI: native content lease и восстановление SQL DOWNLOADING после завершения процесса; проверить две JVM, повторное открытие БД и прежнее audio (REL-04).
- [x] Desktop/TUI: файловый pending-журнал, сверка `.part`/готовых непривязанных файлов после аварии (REL-05). Проверены живой/принудительно завершённый отдельный процесс, SQL-ссылки, отмена и повтор после частичного отказа.
- [x] Зафиксировать консервативную legacy/no-lock политику: исходные данные без доказанного владельца не сбрасывать. Desktop/TUI — read-only аудит и восстановление отдельного профиля через SQLite snapshot (REL-06).

## REL-S3: межвкладочное обновление библиотеки (LK-04)

Спринт завершён и слит в dev через [PR #6](https://github.com/ursusandwolf/langkraft/pull/6), merge `63a6a94`, после успешного [CI](https://github.com/ursusandwolf/langkraft/actions/runs/39777909189). База спринта — dev `384a30f`; история обеих основных веток сохранена.

- [x] Добавить profile-scoped BroadcastChannel hints от BrowserSqlDriver после notify/успешного COMMIT; rollback и входящая invalidation не публикуются.
- [x] Сохранить чтение repository Flow через DatabaseAccess; отложить входящую invalidation до конца активной локальной транзакции.
- [x] Добавить локальное перечитывание по focus/pageshow/возврату видимости; закрывать канал и DOM listeners вместе с client/driver.
- [x] Проверить второй полный Compose-клиент без reload, одновременные записи двух SQLite workers, изоляцию профилей и работу без BroadcastChannel.
- [x] Воспроизвести LK-04 новым тестом на прежней production-сборке и пройти development/production регресс после исправления; 31 Kotlin/Chromium-тест.
- [x] Провести review PR REL-S3 и включить его в dev после успешного GitHub CI (PR #6).

## Backlog после интеграции

- [x] REL-S4: удалённый AI в UI; PR #8 → dev и выпуск PR #9 → main, обе ветки на `0f14ff0`, JVM/Web CI успешны.
- [x] REL-S5 / REL-13: opt-in backend retention новых managed job-каталогов, защита queued/active импортов и HTTP-потоков, GET/HEAD/Range, сохранение legacy. [ADR и ограничения](ADR_BACKEND_MEDIA_RETENTION.md). Общая квота остаётся отдельной задачей.

- [x] Web legacy/no-lock: экспорт/импорт и восстановление в отдельный браузерный профиль без очистки исходного IndexedDB (REL-07). Поддержаны schema v1–v3, VFS v5/audio v1; архив до 64 МиБ.
- [x] Список Web recovery-профилей и частичных копий без открытия БД, выбор копии для запуска/восстановления (REL-08).
- [x] Ручное удаление выбранных новых recovery-v2 профилей (REL-09): lifetime-lock SQL/audio/recovery, постоянный tombstone до очистки, повтор после частичного отказа и запрет старых ссылок. Очищаются записи без deleteDatabase; пустые контейнеры и реестр сохраняются. Основной и старые профили защищены от удаления.
- [ ] Потоковый Web recovery для больших библиотек.
- [x] REL-S7 / REL-15: проверять лимиты Web recovery во время cursor-чтения IndexedDB, до загрузки всего store и создания копии. Chromium-регресс с 66 МиБ сохраняет источник и останавливает чтение на лимите. Потоковый архив остаётся отдельной задачей.
- [x] REL-S6 / REL-14: backend byte-бюджет с резервированием до admission, per-import/total limits, metadata preflight, контроль actual bytes/процессов и удаление intermediates. [ADR](ADR_BACKEND_MEDIA_BUDGET.md). Retention PR #10 включён в dev (`bd6fb11`), CI успешен.
- [ ] Квоты/retention клиентских копий медиа и очистка аварийных следов IndexedDB.
- [ ] Политика профилей аккаунтов и дальнейшая координация нескольких вкладок. LK-04 исправлен в REL-S3; это не общая сериализация всех операций между вкладками.
- [ ] Включение и регресс Android target.
- [ ] Проверка Gemini с реальным ключом и конкретных аудиоустройств/VLC в окружении пользователя.
- [x] Подключить RemoteLinguisticAssistant в клиентском DI: все четыре AI-операции через JWT backend API, общий DTO и маршрут перевода реплики; ошибки/отмена и устаревшие коррекции проверяются тестами (REL-12). Проверка Gemini с реальным ключом остаётся отдельной задачей.

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
