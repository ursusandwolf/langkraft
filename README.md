# Langkraft 🇩🇪

Приложение для изучения немецкого через прослушивание YouTube с субтитрами, контекстный словарь и интервальное повторение.

## Состояние проекта — 22 октября 2026

Проект находится на стадии интеграции и стабилизации. Основой выбрана расширенная реализация **dev**: Desktop/TUI, SRS, авторизация, синхронизация и Gemini. Из main перенесены проверки импорта, процессы с таймаутом, SQLite WASM/IndexedDB и обработка ошибок плеера. Исторические main и dev разошлись на 6 и 51 уникальный коммит; прямое слияние давало 29 конфликтующих файлов.

Исходная dev не компилировалась для JS и имела 7 падающих backend-тестов. Эти ошибки исправлены. В интеграционной версии проверены:

| Область | Проверка |
| --- | --- |
| Общий код и backend | JVM-тесты, включая миграции обеих схем, SRS и синхронизацию двух локальных БД через Ktor/JWT |
| Данные | Повторное открытие, сохранение тегов/SRS/ссылок/очереди sync, защита новой offline-правки от старого ответа сервера |
| Web | Development-сборка и Chromium: ошибка/повтор импорта, job polling, воспроизведение, сохранение слова и аудио, перезагрузка и offline-аудио |
| Хранилище браузера | Реальные SQLite WASM и IndexedDB; завершение worker, rollback, foreign keys и неполные загрузки |
| Desktop/TUI | Сборка и запуск DI/БД/аудиодвижка без окон; TUI проверен через виртуальный терминал |
| Внешние media tools | Реальный импорт Easy German 1: 48 реплик, 156 секунд MP3, 200 точек waveform |

Это не заявление о готовности production. Android target отключён. Работа платных Gemini API и конкретного аудиоустройства/VLC проверяется отдельно в окружении пользователя. Полный запуск Web без сети требует закешированного приложения: offline здесь означает доступ к сохранённой библиотеке и аудио в уже загруженном приложении.

На машине текущей проверки Windows Application Control блокирует ffplay. Поэтому звук Desktop/TUI не подтверждён; проверки DI/БД/запуска пройдены, воспроизведение в Chromium подтверждено.

Актуальные этапы и результаты: [план спринтов](docs/todo.md), [интеграция](docs/INTEGRATION_PLAN_2026-10-21.md), [changelog](docs/CHANGELOG.md), [текстовый UML-журнал](docs/CHANGELOG_UML.md).

## Зависимости

- **JDK 21**. Gradle 8.14 загружается через wrapper; отдельный Gradle не нужен.
- **Node.js 22+ и npm** для браузерных проверок и JavaScript runtime yt-dlp. Локальные проверки выполнялись также на Node 24.
- **yt-dlp, ffmpeg и ffprobe** в PATH для импорта. **ffplay** нужен для TUI и Desktop без VLC.
- Для Desktop предпочтителен **VLC той же разрядности, что JVM**. Если VLC недоступен, используется ffplay.
- Для браузерных тестов — Chromium, устанавливаемый Playwright.

Версии библиотек зафиксированы в Gradle и lock-файле npm: Kotlin 2.0.21, Compose Multiplatform 1.7.0, Ktor 2.3.5, Koin 3.5.0, SQLDelight (plugin 2.0.2/runtime 2.0.0), Exposed 0.46.0, SQLite JDBC, kotlinx.coroutines и kotlinx.datetime. Desktop использует VLCJ, TUI — Lanterna. Библиотеки вручную скачивать не нужно; первый запуск требует сети.

В Windows можно установить проверенные media tools локально:

```powershell
.\tools\media\Setup-MediaTools.ps1
$env:Path = "$(Resolve-Path .\tools\media\build);$env:Path"
```

Скрипт проверяет SHA-256 и извлекает yt-dlp/ffmpeg/ffprobe/ffplay в игнорируемый Git каталог. Альтернатива — собственные установленные инструменты. Для runner можно задать `YT_DLP_EXECUTABLE`, `FFMPEG_EXECUTABLE` и `FFPLAY_EXECUTABLE`; ffmpeg/ffprobe для самого yt-dlp должны быть доступны в PATH.

## Запуск в PowerShell

Из корня репозитория, в первом терминале:

```powershell
# Создать секрет один раз и сохранить его в настройках своего окружения.
$bytes = New-Object byte[] 48
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$env:LANGKRAFT_JWT_SECRET = [Convert]::ToBase64String($bytes)
$rng.Dispose()
.\gradlew.bat :backend:run
```

Backend: `http://localhost:8080`. Без JWT secret длиной хотя бы 32 символа backend не запускается. После смены секрета сохранённые токены потребуют повторного входа. Для Gemini задайте `GEMINI_API_KEY`; без ключа используется mock.

Для длительного backend-процесса параллельно со сборками используйте отдельный дистрибутив: `./gradlew.bat :backend:installDist`, затем `java -cp "backend/build/install/backend/lib/*" io.ktor.server.netty.EngineMain`. Wildcard classpath также обходит ограничение длины Windows batch-команды. Не пересобирайте этот дистрибутив, пока он работает.

Во втором терминале выберите клиент:

```powershell
# Web: открыть http://127.0.0.1:3000
.\gradlew.bat :webApp:jsBrowserDevelopmentRun

# Или Desktop
.\gradlew.bat :shared:run

# Или TUI — запускать в интерактивном терминале
.\gradlew.bat :shared:runTui
```

Web использует текущий origin и proxy `/api` к backend. Desktop/TUI по умолчанию обращаются к `http://localhost:8080`. Вкладка **Learn** импортирует контент, **Review** запускает SRS, **Write** — письменные упражнения. В **Account** доступны регистрация, вход и **Sync now**. Словарь доступен локально без входа; облачная синхронизация требует аккаунта. JWT хранится в локальной БД; Sign out удаляет токен и сохраняет библиотеку. TUI использует профиль Desktop и восстанавливает ранее сохранённый токен.

Для Bash используются `./gradlew` и обычные переменные окружения, например `export LANGKRAFT_JWT_SECRET=...`. Тесты `--smoke` не открывают окна:

```powershell
.\gradlew.bat :shared:run --args=--smoke
.\gradlew.bat :shared:runTui --args=--smoke
```

## Где хранятся данные и как работает миграция

| Данные | По умолчанию | Настройка |
| --- | --- | --- |
| Desktop/TUI | `~/.langkraft/langkraft.db`, аудио в `~/.langkraft/audio` | `LANGKRAFT_DATA_DIR` |
| Backend | `data/langkraft.db` относительно рабочего каталога backend | `LANGKRAFT_BACKEND_DATA_DIR` |
| Исходные медиа backend | `downloads/jobId/` | `LANGKRAFT_DOWNLOADS_DIR` |
| Web | IndexedDB текущего origin: SQLite и отдельное хранилище аудио | Не переносится между разными origin автоматически |

Desktop/Web используют объединённую схему **v3**. Миграция распознаёт dev (включая старую БД с `user_version=0`) и main v1/v2, переносит названия столбцов и добавляет поля без сброса словаря. Повторный импорт сохраняет SRS и offline-ссылку; одинаковые времена реплик не объединяются. Неизвестные локальному клиенту ссылки на исходный контент сохраняются в metadata синхронизации.

Перед Desktop-миграцией создаётся согласованный SQLite backup `langkraft.db.before-schema-3.bak` через `VACUUM INTO`; backend перед обновлением sync-схемы создаёт `.before-sync-v2.bak`. Закройте старые клиенты перед обновлением. Для отката остановите приложение и восстановите backup вместе с прежним кодом: запуск старого main поверх v3 не является безопасным откатом. БД более новой версии отвергается, а не пересоздаётся.

Очистка данных сайта в браузере удаляет и библиотеку, и offline-аудио. Если сохранённое аудио отсутствует, плеер предлагает явное **Play online**, сохраняя словарь и SRS.

## Ограничения импорта

Принимаются HTTPS-ссылки на отдельные YouTube-видео с известной положительной длительностью до 4 часов. По умолчанию разрешены 4 воркера и 8 дополнительных незавершённых заданий; перегрузка возвращает HTTP 503 с `Retry-After`. yt-dlp ограничен 600 секундами, waveform ffmpeg — 120 секундами. Неисправные субтитры отвергаются целиком. Отмена/остановка завершает дерево процессов; незавершённые задания после перезапуска получают ERROR и требуют повторного импорта.

Полная загрузка аудио проверяет HTTP 200, MIME и количество байтов. Это не проверка кодека; файлы должны декодироваться выбранным аудиодвижком. Новые медиа сохраняются как MP3. Выдача backend поддерживает Range для перемотки.

Общие квоты/retention медиа, очистка следов аварийного завершения IndexedDB и координация нескольких вкладок остаются в техническом backlog. Приложение пока не стоит развёртывать как публичный сервис без отдельной проверки эксплуатации и лимитов.

## Проверки и production Web

```powershell
.\gradlew.bat :shared:desktopTest :backend:test
.\gradlew.bat :webApp:jsBrowserDevelopmentWebpack
Push-Location tools/web-storage-tests
npm ci
npx playwright install chromium
npm test
npm run test:app
Pop-Location

.\gradlew.bat :webApp:jsBrowserProductionWebpack
$env:WEB_APP_BUNDLE = Join-Path (Get-Location) 'webApp/build/kotlin-webpack/js/productionExecutable'
Push-Location tools/web-storage-tests
npm run test:app
Pop-Location
Remove-Item Env:WEB_APP_BUNDLE
```

CI выполняет JVM-тесты, smoke Desktop/TUI, storage-проверки и Web-сценарии для development/production. Живой YouTube намеренно запускается отдельно от воспроизводимых тестов:

```powershell
# При запущенном backend и установленных media tools
Push-Location tools/web-storage-tests
npm run test:youtube
Pop-Location
```

Production bundle находится в `webApp/build/kotlin-webpack/js/productionExecutable`. При размещении нужно также отдавать `index.html` и Skiko-ресурсы из `webApp/build/processedResources/js/main`, сохранив имена файлов; `.wasm` отдавать как `application/wasm`, `/api` направлять на backend. Проверки публикации в конкретном hosting-окружении выполняются отдельно.

## Структура проекта

- `shared/commonMain` — модели, Repository, use cases, общий Compose UI и SRS.
- `shared/desktopMain`, `jsMain`, `jvmMain` — платформенные БД, аудио и IO.
- `backend` — Ktor API, auth, sync, задания импорта и CLI Adapter.
- `webApp` — инициализация Web/DI, HTML и proxy разработки.
- `tools` — media installer и Playwright-регресс.
- `androidApp`/`androidMain` — исходники сохранены; Android не включён в текущую сборку.
