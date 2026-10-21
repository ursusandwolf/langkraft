# Changelog

## [Unreleased]
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
