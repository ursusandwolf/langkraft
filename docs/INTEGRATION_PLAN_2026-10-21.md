# Интеграция main в основу dev — 2026-10-21

Статус: аудит выполнен; слияние и публикация не выполнены. Готовность к выпуску не подтверждена.

## INT-04 — выполнен второй спринт, 2026-10-21

В рабочей копии защищены аудиоответы (HTTP 200, MIME, пустое тело, Content-Length), отмена и импорт. Ограничены принятые задания (worker_count + queue_capacity), неверный URL не создаёт job, перегрузка даёт HTTP 503 с Retry-After. Сохраняется контракт jobId/polling; HTTP-disconnect не отменяет принятый job.

Перенесён и адаптирован ProcessBuilder runner main: MP3 dev, отдельный каталог на jobId, таймауты yt-dlp 600 с / ffmpeg 120 с, уничтожение дерева при отмене, удаление временных логов. Парсер строго проверяет SRT, сохраняет VTT и не объединяет реплики с одинаковыми временами. Waveform хранит в памяти только 200 корзин. Импорт допускает известную положительную длительность до 4 часов. Подробности: changelog INT-04.

Автотесты: **28 shared + 26 backend = 54 успешно**, JS-компиляция shared/webApp успешна. HTTP-обработчики проверены в Ktor test application; timeout/cancellation — на реальных Java-процессах и их потомках. yt-dlp и ffmpeg в сценариях импорта заменены тестовыми runner; реальное медиа и платформенный регресс относятся к INT-S4.

Следующий спринт — INT-S3 (данные и Web). Production-схема, SRS и sync не менялись. Остались Web DI/хранилище, связи и порядок реплик при записи, миграции, квоты/retention, восстановление незавершённых заданий после перезапуска и конфигурационный JWT secret перед внешним развёртыванием.

## Прогресс после аудита — INT-03, 2026-10-21

Четыре спринта зафиксированы в [roadmap](todo.md). Первый пункт пользователя — восстановление тестов и JS-компиляции — выполнен в интеграционной рабочей копии: shared 16/16, backend 11/11, compileKotlinJs shared и webApp успешны. Полная команда: `./gradlew :shared:desktopTest :backend:test :shared:compileKotlinJs :webApp:compileKotlinJs` (в PowerShell использовать `./gradlew.bat`).

Исправлены изоляция/жизненный цикл тестовых SQLite-БД, временные данные cleanup, неоднозначное имя userId в тестовой SQL-вставке. Общий код использует платформенный ioDispatcher и kotlinx.datetime.Clock. Desktop сохраняет Dispatchers.IO; JS использует Default. Android-реализация добавлена, но Android target не включён и не проверен.

Таблица находок и исходные результаты ниже фиксируют состояние dev **до INT-03**. Они сохранены для сравнения; ошибки тестов и JS-компиляции уже устранены в рабочей копии. Широкий этап S0 ниже также включает CI, упаковку Web и запуск платформ — эти проверки ещё предстоят. Успешная компиляция не устраняет отсутствующие Web DI/хранилище.

## Зафиксированная база

- Основа: `origin/dev`, `3003225dedf05b42523a70f3c95acd02e82a8dc2`.
- Источник исправлений: `origin/main`, `945ab3533a0b067041974680be5b6e216f220b17`.
- Общий предок: `1831c5ab77afa93790a7c3937811956559d96b04`.
- Уникальные коммиты: main — 6, dev — 51. Всего достижимых: 8 и 53 соответственно.
- Изолированная ветка: `integration/dev-base-20261021`, worktree `build/integration-dev`.
- `git merge-tree --write-tree origin/dev origin/main`: 29 конфликтующих путей. Рабочие деревья и указатели main/dev не менялись.

## Решение

Сохраняем архитектуру и версии dev: Kotlin 2.0.21, Compose 1.7.0, асинхронные задания импорта, авторизацию, синхронизацию, SRS, Desktop и TUI. Переносим исправления main небольшими адаптированными патчами. Стратегия автоматического выбора всей стороны (`ours`/`theirs`) неприемлема: она скрывает несовместимость контрактов.

Коммиты main объединяют изменения разных подсистем. Безопасность целого cherry-pick не подтверждена ни для одного такого коммита. Отсутствие текстового конфликта также не гарантирует совместимость.

## Подтверждённые по коду препятствия

| Приоритет | Место в dev | Наблюдение и последствие |
| --- | --- | --- |
| P1 | `backend/src/test/kotlin/com/langkraft/backend/IngestionJobRepositoryTest.kt`, `backend/src/test/kotlin/com/langkraft/backend/db/BackendVocabularyRepositoryTest.kt` | В исходной dev падают все 7 тестов репозиториев: SQLite no such table ingestion_jobs/vocabulary_sync. Shared-memory SQLite создаётся в setup-транзакции без постоянного соединения: после закрытия последнего соединения БД исчезает. Исправление: уникальная временная файловая БД для каждого теста либо явно удерживаемое соединение и гарантированная очистка. |
| P1 | `shared/src/commonMain/kotlin/com/langkraft/data/sync/SyncManager.kt:49`, `shared/src/commonMain/kotlin/com/langkraft/ui/player/PlayerViewModel.kt:95,155` | Подтверждённый провал compileKotlinJs: Dispatchers.IO и System.currentTimeMillis недоступны в JS. До интеграции Web исправить платформенные зависимости общего кода; dispatcher внедрять с учётом JDBC Desktop, время брать из multiplatform API. |
| P1 | `webApp/src/jsMain/kotlin/Main.kt:16`, `shared/src/commonMain/kotlin/com/langkraft/di/Koin.kt` | Web вызывает только `initKoin()`. Common-модуль требует AppDatabase, но не регистрирует её; AudioPlayer тоже отсутствует. Desktop регистрирует их отдельно. Создание DashboardViewModel в Web требует отсутствующую БД. |
| P1 | `shared/src/jsMain/kotlin/com/langkraft/io/FileSystemImpl.kt:7` | writeBytes/rename — пустые методы. Download COMPLETED не доказывает сохранение файла в браузере. |
| P1 | `backend/src/main/kotlin/com/langkraft/backend/YouTubeIngestionService.kt:30` | Channel.UNLIMITED, startIngestion без проверки URL; количество воркеров ограничено, число принятых заданий — нет. |
| P1 | `backend/src/main/kotlin/com/langkraft/backend/YtdlpClient.kt` | Внешние процессы без явного таймаута и уничтожения дерева при отмене; нет явного ограничения одиночным видео. Требуется адаптация runner из main. |
| P1 | `backend/src/main/kotlin/com/langkraft/backend/YouTubeIngestionService.kt:141` | ffmpeg stderr не вычитывается, waitFor без таймаута, PCM целиком накапливается в памяти. Возможны блокировка процесса и рост памяти; одними исправлениями yt-dlp это не покрыть. |
| P1 | `backend/src/main/kotlin/com/langkraft/backend/SrtParser.kt:19` | mapNotNull молча пропускает повреждённые блоки; ID зависит только от времён: одинаковые интервалы имеют одинаковые ID и при сохранении могут перезаписаться. |
| P1 | `shared/src/commonMain/sqldelight/com/langkraft/db/AppDatabase.sq:56` | INSERT OR REPLACE для Content/SubtitleLine и удаление всех субтитров при повторном сохранении. При включённых FK возможны нежелательные каскады и обнуление ссылок словаря. Нужно проверять с FK ON. |
| P1 | `backend/src/main/resources/application.conf:12` | JWT secret задан известной строкой; до внешнего развёртывания необходим конфигурационный секрет и проверка его наличия. |
| P2 | `shared/src/commonMain/kotlin/com/langkraft/data/repository/AudioDownloaderImpl.kt:42` | Тело ответа пишется без явной проверки успешного статуса и аудио MIME. Backend выдаёт mp3, локальное имя — .opus: согласовать формат. |
| P2 | `shared/src/commonMain/kotlin/com/langkraft/domain/usecase/IngestContentUseCase.kt:15` | runCatching захватывает CancellationException. В воркерах импорта также широкие catch Exception. |
| P2 | `shared/src/desktopMain/kotlin/com/langkraft/di/DesktopModule.kt` | Для существующей БД нет вызова Schema.migrate. Перенос ordinal и схемы main без миграционного плана опасен. |

`DownloadCleanup` существует, но поиск ссылок в backend/src/main обнаружил только само объявление. Наличие класса не подтверждает работающую очистку медиа. Периодическая очистка IngestionJobRepository — отдельная операция.

## Порядок спринтов и критерии допуска

| Этап | Изменения | Что обязательно проверить |
| --- | --- | --- |
| S0 | Восстановить JS-компиляцию (IO dispatcher и System в commonMain) и изоляцию SQLite backend-тестов; зафиксировать JVM/JS baseline, добавить CI под версии dev | shared:desktopTest, backend:test, компиляция и сборка Web; отдельно реальный запуск Desktop/TUI. В cleanup-тесте заменить фиксированные 100/5000 мс на времена относительно текущего часа или внедрить Clock: сейчас обе записи давно истекли. Между тестами не должны сохраняться w1/job1. Нельзя объявлять успешными тесты main для dev. |
| S1, небольшие патчи | Проверки HTTP status/MIME перед записью; сохранение CancellationException; валидация YouTube URL; конфигурация JWT | HTML/ошибка сервера не сохраняются как аудио; отмена не превращается в обычную ошибку; неподдерживаемые URL отклоняются до создания задания; production без секрета не стартует. |
| S2, backend | Адаптировать ProcessBuilder runner main под MP3 и API заданий dev; ограничить очередь с явным отказом при переполнении; исправить lifecycle/timeout ffmpeg; строгий парсер | Сохраняются jobId/polling; один URL не разворачивается в плейлист; процессы завершаются при timeout/shutdown; worker продолжает работу после обычной ошибки. SRT и поддерживаемый dev VTT сохраняются, повреждённые данные отвергаются, одинаковые времена не теряют реплики. |
| S3, данные | UPDATE/INSERT вместо REPLACE; стабильные ID/порядок реплик; версионирование существующих desktop-БД; сохранение полей sync/SRS | Повторный импорт не теряет словарь, связи, теги, историю SRS и очередь синхронизации; миграции реальных схем обеих веток и rollback на копии данных. Не копировать 1.sqm из main без согласования схем. |
| S4, Web | Портировать SQLite WASM + IndexedDB из main с async-доступом, подключить JS DI и движок Ktor, затем ошибки плеера/онлайн-восстановление | Открытие Dashboard, импорт, воспроизведение, перезагрузка и offline; ошибки квоты/хранилища; отсутствие потерь sync/SRS; desktop продолжает собираться. Это сквозной перенос, а не копирование одного worker. |
| S5, выпуск | README под итоговые команды и зависимости, адаптированный CI; полный регресс | Авторизация, sync между двумя клиентами, SRS, импорт, Web/Desktop/TUI. Реальное YouTube/ffmpeg испытание отдельно от тестов с заглушками. |

Проверки из main переносить вместе с поведением, адаптируя интерфейсы. Не переносить HTTP-disconnect cancellation main на постоянные задания dev: закрытие POST после получения jobId не должно отменять работу.

## Как завершать интеграцию

1. Каждый патч основан на dev, имеет отдельный diff, проверки и запись в changelog/UML.
2. После регресса — PR integration → dev. Перед слиянием проверить, что dev не ушла вперёд относительно зафиксированного SHA; при необходимости повторить проверки на обновлённой базе.
3. После стабилизации — PR dev → main с сохранением истории обеих веток. Оставшиеся конфликты main разрешать вручную по итоговой архитектуре, а не переписыванием main или force push.
4. Перед изменением данных — резервная копия реальных локальных БД. Возврат к старому коду после миграции не является гарантированным откатом данных.

## Проверки в этой сессии

- Статическое сравнение веток и пробное merge-tree выполнены.
- `:shared:desktopTest :backend:test --offline`: FAILED на конфигурации, до выполнения тестов. В кеше нет plugin marker `org.jetbrains.kotlin.plugin.serialization:2.0.21`.
- Сеть позволила получить зависимости и пройти конфигурацию. `:shared:desktopTest :backend:test`: shared — 16/16 успешно; backend — 4/11 успешно, 7/11 FAILED. Все 7 ошибок — SQLite no such table в двух тестовых классах репозиториев. Сборка JVM-кода прошла; общий Gradle запуск завершился ошибкой тестов. HTML-отчёты: `shared/build/reports/tests/desktopTest/index.html`, `backend/build/reports/tests/test/index.html`.
- `:shared:compileKotlinJs :webApp:compileKotlinJs`: FAILED на shared:compileKotlinJs. Unresolved reference IO (SyncManager:49), System (PlayerViewModel:95,155), сопутствующая ошибка compareTo. До webApp компиляция не дошла. Запуск UI и сохранение Web на этой базе не подтверждены.
- На момент исходного аудита код приложения не менялся. Последующие исправления INT-03 описаны в начале документа; готовность merge ещё не подтверждена.
