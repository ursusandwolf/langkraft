# INT-10 — 2026-10-22 — завершена интеграция dev/main

- После успешных CI push/PR слиты integration → dev (#1, `8675897`) и dev → main (#2, `9576ab4`). Деревья проверенной интеграции, dev и main совпали; история обеих веток сохранена.
- Завершены четыре интеграционных спринта. README, план и итоговый отчёт содержат актуальный статус, команды запуска, 83 JVM + 26 Kotlin/Chromium тестов и ограничения внешних проверок.
- Подготовлен архиватор исходников без тестов, с инструкцией AI-ревьюеру, manifest и проверкой ZIP/SHA-256. Временные тестовые серверы остановлены.

# INT-09 — 2026-10-22 — восстановление Web при POSIX locale

- CI выявил падение Compose при создании Intl.Locale из POSIX-значений navigator.languages (например C/en_US.UTF-8). Перед запуском UI добавлена нормализация BCP-47, фильтрация некорректных тегов и fallback en-US. Корректные предпочтения браузера сохраняются.
- Web regression подставляет некорректные locale-значения и проверяет нормализацию вместе с импортом, сохранением и offline-воспроизведением. Ошибки браузера записываются со stack trace.
- Добавлены отдельный Karma launcher для Ubuntu Actions и архиватор исходников без тестов с manifest и проверкой SHA-256.

# INT-08 — 2026-10-22 — восстановлен Kotlin/JS регресс

- Дополнительный Chromium-прогон обнаружил `IrLinkageError`: Compose разрешал coroutines-core 1.8.0, а test оставался 1.7.3. Core/test/swing выровнены на 1.8.0.
- Повторно прошли 83 JVM-теста и 26 общих Kotlin-тестов в настоящем Chromium. `:shared:jsBrowserTest` включён в CI вместе с Playwright storage/UI development/production.
- Инструкция AI-кодревью обновлена под async jobs, MP3, schema v3, SRS/auth/sync и текущие границы проверки.

# INT-07 — 2026-10-22 — ручная интеграция истории main

- Разобраны конфликты main с dev-базой. Сохранены async jobs, SRS, sync, Desktop/TUI и общая Web-навигация; решения описаны в `INTEGRATION_RESOLUTION.md`.
- Перенесены периодическое обновление due words, стабильный ID сохраняемого слова, защита повторной записи, ошибки/повтор перевода, отмена загрузки и изоляция поздних ответов. Audio errors привязаны к request ID.
- Добавлены шесть регрессионных тестов PlayerRaceTest и активные строгие parser cases из main. Исходные несовместимые fixtures сохранены в `archive/main-reference` с картой замен.
- Build сохраняет Kotlin 2/Compose 1.7; исправлено обновление локального npm storage package, обновлён Yarn lock. Web regression ждёт готовности Compose вместо фиксированного первого клика.
- Повторный реальный YouTube импорт: Easy German 1, 48 реплик, 156 секунд, 200 ненулевых waveform bins; HTTP Range возвращает 206 и 256 запрошенных байтов.
- Проверки и финальные ссылки CI/PR фиксируются в `INTEGRATION_COMPLETION.md` после выполнения.

# Changelog

## [Unreleased]
### Integrated data and Web — INT-05 — 2026-10-22
- Согласована схема Desktop/Web v3 для исторических main v1/v2 и dev, включая user_version=0. Миграции выполняются транзакционно; Desktop создаёт согласованный VACUUM INTO backup и отвергает неизвестную новую версию.
- Репозитории переведены на async SQLDelight и единый DatabaseAccess с Mutex. PersistentJdbcDriver явно владеет одним JDBC-соединением: устранена подтверждённая на Windows утечка открытых файлов после транзакций.
- UPDATE/INSERT вместо REPLACE сохраняет связи словаря и SRS. ordinal сохраняет порядок реплик с одинаковыми временами; waveform и offline-ссылка переживают повторный импорт. Проверяются владение репликами и дубли ID.
- Ссылки на ещё не импортированный источник сохраняются в SyncMetadata; локальные FK остаются валидными. Повторный импорт восстанавливает доступные связи. Сохраняются теги, lapseCount, lastUpdated и очередь изменений.
- Аудио записывается потоком в файл/IndexedDB. Неполные загрузки не публикуются, отмена удаляет временные данные; при ошибке записи ссылки в БД опубликованный asset удаляется.
- Web подключает SQLite WASM worker, IndexedDB, Ktor JS, DI и общую навигацию Desktop (библиотека/плеер/SRS/письмо). Нативное поле URL исправляет ввод/вставку в Canvas UI. Устранена runtime ABI-ошибка старого koin-compose через Koin core API.
- Перенесены ошибки браузерного аудио и явный Play online при отсутствии локального asset; свежие HTMLAudioElement и generation guards изолируют события старого источника.
- Регресс: 36 shared + 27 backend JVM-тестов; Chromium storage (3 сценария), полный development/production Web-сценарий, перезагрузка/offline и мобильный viewport.

### Auth and final regression — INT-06 — 2026-10-22
- Добавлены восстановление JWT и Account/Sync now в Desktop/Web. BackendUserRepository явно получает свою БД; backend требует LANGKRAFT_JWT_SECRET вместо известной строки. TUI восстанавливает профиль Desktop.
- Синхронизация использует серверный монотонный курсор, tombstones удаления и JSON-теги с поддержкой старого CSV. Две локальные SQLite БД проверены через настоящие Ktor routes/JWT с переносом SRS/тегов/исходных ссылок и удалением.
- Подтверждение sync удаляет только отправленную ревизию; новая локальная правка во время запроса остаётся в очереди и не перезаписывается старым ответом. Добавлен регрессионный тест этой гонки.
- Backend создаёт backup перед расширением sync-схемы и восстанавливает прерванные импортные задания в ERROR после перезапуска. Добавлен PartialContent; Range проверен на Ktor API.
- Desktop использует ffplay при отсутствии VLC; добавлены CLI smoke режимы Desktop/TUI без окон. ffplay поддерживает выбранную скорость и снимает shutdown hook при release.
- Реальный YouTube/yt-dlp/ffmpeg импорт: Easy German 1, 48 реплик, 156 секунд, waveform 200 точек. Оптимизированная Web-сборка проходит тот же сценарий, что development. Внешний Gemini не проверялся без API key.
- Добавлен CI для integration/dev/main: JVM, Desktop/TUI smoke, Chromium storage и development/production UI. README переписан под итоговый стек, запуск, зависимости, данные, миграции и ограничения.
- Ограничение окружения: Windows Application Control блокирует запуск ffplay. Обход не выполнялся; реальный вывод звука Desktop/TUI не подтверждён. Desktop wiring и виртуальный TUI прошли; ffmpeg/YouTube и Web-аудио проверены отдельно.

### Fixed — INT-04 — 2026-10-21 — защита аудио и импорта
- INT-S2 выполнен в интеграционной рабочей копии. Аудиозагрузка проверяет HTTP 200, MIME, непустое тело и Content-Length до записи; новые файлы сохраняются как MP3. Ошибка/отмена удаляет временный файл, отмена возвращает статус IDLE и пробрасывается далее.
- CancellationException сохраняется в use case, sync и offline delegate; флаг импорта сбрасывается в finally.
- Добавлена валидация HTTPS YouTube video URL до создания задания. Приём ограничен суммой worker_count + queue_capacity (по умолчанию 4 + 8); перегрузка возвращает 503 / Retry-After: 5, неверный URL — 400.
- yt-dlp wrapper заменён ProcessBuilder Adapter без shell и пользовательского конфига; --no-playlist сохраняет одиночный импорт. Таймаут yt-dlp — 600 с, ffmpeg — 120 с; отмена/таймаут уничтожает дерево процессов и очищает логи. Удалена зависимость youtubedl-java.
- Для каждого задания выделен отдельный каталог downloads/jobId; аудио URL включает jobId, ID контента остаётся ID видео. Неудачные задания очищают только свой каталог; ошибки воркера не останавливают очередь. При остановке приложения приём закрывается, активные/ожидающие задания помечаются ERROR. В Koin явно зарегистрирована конфигурация приложения.
- WaveformExtractor обрабатывает PCM из временного файла в 200 корзин вместо хранения всего аудио в памяти; stderr процессов не блокирует pipe, PCM удаляется в finally. Импорт ограничен видео с известной положительной длительностью до 4 часов.
- SRT теперь отвергается целиком при повреждении, проверяются интервалы/индексы/переполнение; сохранена поддержка VTT. Одинаковые интервалы получают разные стабильные ID, прежний ID первой реплики сохраняется. Стабильный порядок после записи в БД и миграции остаются INT-S3.
- Проверки: 28/28 shared и 26/26 backend JVM-тестов; compileKotlinJs shared/webApp успешны. Покрыты HTTP 503/400, MIME/тело, отмена, реальный timeout и дерево Java-процессов, очередь/восстановление воркера, повторный импорт одного видео, SRT/VTT и waveform. Реальный YouTube, ffmpeg CLI, Web runtime и платформенный регресс ещё не проверены.

### Fixed / sprint plan — INT-03 — 2026-10-21
- В roadmap зафиксированы четыре спринта: baseline dev; точечные исправления main; миграции/Web storage; регресс и слияния integration → dev → main.
- Восстановлена JS-компиляция: платформенный ioDispatcher сохраняет IO на JVM и использует Default в JS; PlayerViewModel использует kotlinx.datetime.Clock.
- Backend-тесты изолированы через JUnit TempDir и BeforeEach; исправлены времена cleanup, проверка интервала времени обновления и затенение userId в SQL-вставке. Production-репозитории и схема БД не менялись.
- Проверки: 16 shared + 11 backend JVM-тестов успешно; compileKotlinJs shared/webApp успешно. README и план обновлены; Android, Web runtime и полный регресс ещё не проверены.

### Documentation — INT-02 — 2026-10-21
- README описывает стадию стабилизации, расхождение main/dev, базу интеграции и фактические результаты проверок dev.
- Исправлены устаревшие сведения о статусе AI/SRS, формате аудио, платформах и доступности Android/Web. Добавлены команды воспроизведения baseline и ссылки на план интеграции.
- Изменений классов и шаблонов проектирования нет; запись INT-02 добавлена в UML-журнал.

### Integration audit — INT-01 — 2026-10-21
- Принята dev (`3003225`) как база изолированной ветки `integration/dev-base-20261021`; main/dev не изменены.
- Пробное слияние выявило 29 конфликтующих путей. Добавлен `INTEGRATION_PLAN_2026-10-21.md` с препятствиями, этапами переноса и критериями допуска.
- Добавлен текстовый UML-журнал `CHANGELOG_UML.md` с существующими Repository/Adapter и платформенными реализациями AudioPlayer.
- Первоначальная offline-проверка заблокирована отсутствующим Kotlin serialization plugin 2.0.21; сетевой запуск прошёл конфигурацию. Выявлен провал JS-компиляции исходной dev: Dispatchers.IO и System в commonMain. Изменений кода приложения нет.
- Итог JVM baseline dev: shared 16/16; backend 4/11, семь ошибок SQLite no such table в тестах репозиториев. План S0 дополнен восстановлением жизненного цикла тестовых БД, изоляцией данных и корректными временными метками cleanup-теста.

### Added
- **Audio Waveform Extraction:** Implemented backend waveform generation using `ffmpeg` to extract normalized audio amplitudes for player visualization.
- **Persistent Backend Tests:** Stabilized backend repository tests using shared-memory SQLite configuration and JUnit5 Lifecycle management.

### Fixed
- **TUI Resource Cleanup:** Fixed memory leaks in TUI player by ensuring `onCleared()` is called for ViewModels.
- **Desktop DI Lifecycle:** Fixed ViewModel lifecycle management in Compose Desktop using `rememberViewModel` helper with `DisposableEffect`.
- **FileSystem Reliability:** Switched to `java.nio.file.Files.move` with atomic operations for safer file renames.
- **Sync Protocol Stability:** Fixed `SyncManager` throttling logic to correctly handle connectivity errors.

## [0.5.0] - 2026-05-13
### Added
... (rest of the file remains same)
