# Запуск Langkraft

Актуально для Kotlin 2.0.21 / Compose 1.7.0 и клиентской схемы v3. Все команды выполняются из корня checkout. Для проверки UI достаточно Web и backend; VLC не требуется.

## 1. Подготовить окружение

| Зависимость | Назначение | Как проверить |
| --- | --- | --- |
| JDK 21, JAVA_HOME | Gradle, JVM backend/Desktop/TUI | `java -version`, `./gradlew.bat --version` |
| Node.js 22+ и npm | Web-сборка и JS runtime yt-dlp | `node --version`, `npm --version` |
| yt-dlp | Получение YouTube metadata/media/subtitles | `yt-dlp --version` |
| ffmpeg и ffprobe | Конвертация MP3 и waveform | `ffmpeg -version`, `ffprobe -version` |
| VLC той же разрядности, что JDK | Предпочтительный Desktop audio engine | Установленный native VLC |
| ffplay | TUI, fallback Desktop без VLC | `ffplay -version` |
| Chromium через Playwright | Автоматические browser-тесты | Установка ниже |
| Python 3.10+ | Только упаковка review ZIP | `python --version` |

Gradle 8.14 установлен через Wrapper; системный Gradle не нужен. Kotlin, Compose, coroutines 1.8.0, Ktor 2.3.5, SQLDelight plugin 2.0.2/runtime 2.0.0, Exposed 0.46.0, Koin 3.5.0, SQLite JDBC, VLCJ и Lanterna скачиваются по Gradle-конфигурации. Browser test tooling закреплён в `tools/web-storage-tests/package-lock.json`, Web npm-зависимости — в `kotlin-js-store/yarn.lock`. Первый запуск требует сети; Gradle использует установленный Node.

На Windows media tools можно установить скриптом с проверкой SHA-256:

```powershell
.\tools\media\Setup-MediaTools.ps1
$env:Path = "$(Resolve-Path .\tools\media\build);$env:Path"
```

PATH меняется только в текущем терминале. Backend должен наследовать PATH с media tools и Node. Переопределения `YT_DLP_EXECUTABLE`, `FFMPEG_EXECUTABLE`, `FFPLAY_EXECUTABLE` задают конкретные исполняемые файлы; ffmpeg/ffprobe для самого yt-dlp также должны быть доступны в PATH.

## 2. Запустить backend в первом терминале

PowerShell:

```powershell
$projectDir = (Get-Location).Path
$env:LANGKRAFT_BACKEND_DATA_DIR = Join-Path $projectDir 'backend/data'
$env:LANGKRAFT_DOWNLOADS_DIR = Join-Path $projectDir 'downloads'

# Создать секрет один раз, сохранить в настройках окружения и не коммитить.
$secretBytes = New-Object byte[] 48
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($secretBytes)
$env:LANGKRAFT_JWT_SECRET = [Convert]::ToBase64String($secretBytes)
$rng.Dispose()

.\gradlew.bat :backend:run
```

Секрет должен содержать минимум 32 символа. Повторная генерация при каждом запуске инвалидирует прежние JWT: используйте сохранённое значение в следующих сессиях. Не печатайте секрет в логи. Файл `.env` автоматически приложением не загружается.

Bash на Linux/macOS после установки JDK/Node/media tools:

```bash
export LANGKRAFT_BACKEND_DATA_DIR="$PWD/backend/data"
export LANGKRAFT_DOWNLOADS_DIR="$PWD/downloads"
# Также создать один раз и сохранить для следующих запусков.
export LANGKRAFT_JWT_SECRET="$(node -e "process.stdout.write(require('node:crypto').randomBytes(48).toString('hex'))")"
bash ./gradlew :backend:run
```

Проверка из другого терминала: `curl http://localhost:8080/` или `Invoke-WebRequest http://localhost:8080/`. Ожидается HTTP 200 и `Langkraft Backend is running`.

Для долгого процесса параллельно со сборками используйте отдельный дистрибутив:

```powershell
.\gradlew.bat :backend:installDist
java -cp "backend/build/install/backend/lib/*" io.ktor.server.netty.EngineMain
```

Запускать из корня с тем же окружением. Не пересобирайте этот installDist, пока он работает. Wildcard classpath помогает обойти ограничение длины Windows batch-команды. Без backend локальная сохранённая библиотека доступна, но импорт, вход и sync не работают.

## 3. Выбрать клиент во втором терминале

| Клиент | PowerShell | Результат |
| --- | --- | --- |
| Web | `.\gradlew.bat :webApp:jsBrowserDevelopmentRun` | Открыть `http://127.0.0.1:3000` |
| Desktop | `.\gradlew.bat :shared:run` | Compose JVM окно |
| TUI | `.\gradlew.bat :shared:runTui` | Lanterna в интерактивном терминале |

В Bash используйте `bash ./gradlew` вместо `.\gradlew.bat`. Web proxy `/api` указывает на `127.0.0.1:8080`; Web HttpClient использует origin открытой страницы. Desktop/TUI по умолчанию используют `http://localhost:8080` из `AppConfig`. Это DI-конфигурация, отдельной переменной BACKEND_URL сейчас нет. Для другого backend переопределите AppConfig в composition root; проверьте proxy и JWT issuer/audience отдельно.

Не чередуйте `localhost:3000` и `127.0.0.1:3000` в качестве профиля: браузер считает их разными origin, библиотеки IndexedDB различаются.

## 4. Проверить основной сценарий

1. В Web/Desktop открыть **Learn**, вставить HTTPS-ссылку на отдельное YouTube-видео с немецкими субтитрами, нажать Import. Клиент получает jobId и опрашивает статус каждую секунду.
2. Открыть видео из библиотеки, запустить Play, проверить перемотку по реплике, loop и скорость.
3. Нажать Download для offline-аудио; импорт сам по себе не означает, что аудио скачано в клиент.
4. Войти через **Account**, выбрать слово, дождаться перевода от backend, добавить в словарь; проверить **Review** и **Stats**.
5. В **Account** зарегистрироваться/войти и нажать **Sync now**. Локальная библиотека не требует аккаунта, sync требует JWT. Sign out удаляет токен, сохраняя локальные данные. Разделение локальной БД между разными аккаунтами ещё не реализовано.
6. Перезагрузить Web, открыть сохранённый контент и проверить offline-аудио внутри загруженного приложения. Полного offline-запуска приложения без сети нет: service worker не добавлен.

В клиентском `commonModule` подключён `RemoteLinguisticAssistant`, использующий backend URL и актуальный JWT общей HTTP-сессии. Все четыре операции требуют входа через **Account**. `GEMINI_API_KEY` на backend включает настоящий Gemini для перевода слова/реплики, анализа и коррекции в UI; без ключа backend использует mock. Перезапустите backend после настройки ключа. При 401 войдите повторно; сеть/ошибки сервиса показываются в UI без mock-fallback. API Gemini с реальным ключом не входит в текущую проверку.

## Конфигурация и данные

| Параметр | Эффект / значение по умолчанию |
| --- | --- |
| `LANGKRAFT_JWT_SECRET` | Обязательный backend secret ≥32 символов |
| `GEMINI_API_KEY` | Опциональный ключ backend AI; без него mock |
| `LANGKRAFT_BACKEND_DATA_DIR` | Backend `langkraft.db`; default `data` относительно cwd процесса |
| `LANGKRAFT_DOWNLOADS_DIR` | Backend media в каталогах jobId; default `downloads` относительно cwd |
| `LANGKRAFT_DATA_DIR` | Desktop/TUI профиль; default `~/.langkraft`, внутри DB, `audio/` и `download-locks/` |
| `YT_DLP_EXECUTABLE` | Исполняемый yt-dlp; default `yt-dlp` |
| `FFMPEG_EXECUTABLE` | Исполняемый ffmpeg для waveform; default `ffmpeg` |
| `FFPLAY_EXECUTABLE` | Исполняемый ffplay; default `ffplay` |

Порт 8080, host `0.0.0.0`, JWT issuer/audience/24h validity и параметры worker/queue/cleanup заданы в [application.conf](../backend/src/main/resources/application.conf). Worker count=4, queue capacity=8, cleanup interval=1 час, job expiry=24 часа. Это очистка **записей jobs**, а не реализованная TTL-политика всех media-файлов. Импорт ограничен известной положительной длительностью до четырёх часов; yt-dlp timeout 600 с, waveform ffmpeg 120 с. Общая дисковая квота остаётся backlog.

Client DB v3 использует SQLDelight, backend DB — Exposed/SQLite. Это разные базы, несмотря на одинаковое имя `langkraft.db`: не направляйте обе переменные каталогов в один каталог. Web хранит SQLite и audio в IndexedDB выбранного origin. Desktop/TUI используют общий профиль; native content lock и SQL recovery проверены в отдельных JVM. Полная конкурентная работа SRS/sync/migrations с общим профилем отдельно не подтверждена.

Desktop/TUI восстанавливают помеченный DOWNLOADING после завершения владельца: ERROR без прежнего audio либо COMPLETED с прежней ссылкой. Активные/legacy/неизвестные операции не сбрасываются. Пустые файлы download-locks сохраняются между запусками; не удаляйте/переименовывайте их при работающих процессах. Подробности — [ADR recovery](ADR_AUDIO_DOWNLOAD_RECOVERY.md).

Новые файловые загрузки до создания `.part` сохраняют sidecar `<asset>.mp3.pending`. После SQL link журнал снимается. При следующем старте свободный content lock и проверка всех SQL-ссылок разрешают убрать непривязанные MP3/`.part`; журнал удаляется последним, чтобы незавершённую уборку можно было повторить. Не удаляйте sidecar вручную для активных/неразобранных загрузок. Старые, неизвестные и повреждённые записи автоматически не удаляются. Подтверждённое аудио после удаления Content относится к отдельной, пока не реализованной retention-политике.

Для legacy/no-lock доступен [read-only аудит и recovery отдельной копии Desktop/TUI](LEGACY_RECOVERY.md): `python tools/recovery/recover_legacy_profile.py --source <profile>`; добавьте `--clone-to <new-profile>` для восстановления в новый каталог. Нужен Python 3.10+, дополнительных пакетов нет. Исходный профиль сохраняется; копия требует повторного входа в аккаунт.

Миграция клиента распознаёт dev v0/v1 и main v1/v2, сохраняя SRS, tags и pending sync. Перед JVM-миграцией создаётся `langkraft.db.before-schema-3.bak`; backend создаёт `.before-sync-v2.bak`. Закройте старые клиенты до обновления. Для отката остановите процессы и восстановите backup **вместе с совместимым кодом**. Старый main не следует запускать поверх v3. Очистка данных сайта удаляет локальную библиотеку и аудио; это не диагностика без потери данных.

## Проверки

Для восстановления старых Web-загрузок откройте `/recovery.html` на origin сохранённой библиотеки (локально `http://127.0.0.1:3000/recovery.html`). Страница создаёт отдельный профиль, экспортирует JSON-архив до 64 МиБ и импортирует его в новую копию. Исходный IndexedDB сохраняется; копия открывается по ссылке `/?profile=recovered-<UUID>` без входа в аккаунт. Требования, ограничения и порядок проверки: [LEGACY_RECOVERY](LEGACY_RECOVERY.md).

```powershell
.\gradlew.bat :shared:desktopTest :backend:test
.\gradlew.bat :webApp:jsBrowserDevelopmentWebpack

Push-Location tools/web-storage-tests
npm ci
npx playwright install chromium
$env:CHROME_BIN = node -p "require('playwright').chromium.executablePath()"
npm test
npm run test:app
Pop-Location
.\gradlew.bat :shared:jsBrowserTest
```

Для `jsBrowserTest` нужен Chrome/Chromium. Если системного Chrome нет, сначала установите Chromium через Playwright и задайте полный путь исполняемого файла в `CHROME_BIN`. В Linux CI этот путь вычисляется автоматически. Зависимости ОС Chromium устанавливаются `npx playwright install --with-deps chromium` на поддерживаемом Linux-окружении.

Раздельные smoke, без окон и с временным профилем:

```powershell
$env:LANGKRAFT_DATA_DIR = Join-Path $PWD 'build/smoke/manual'
.\gradlew.bat :shared:run --args=--smoke
.\gradlew.bat :shared:runTui --args=--smoke
Remove-Item Env:LANGKRAFT_DATA_DIR
```

Это проверка DI/БД/терминала, а не слышимого звука. Реальный внешний импорт запускается отдельно при работающем backend: `npm run test:youtube` из `tools/web-storage-tests`. Он использует YouTube и пишет медиа. Основной CI использует воспроизводимый локальный API fixture.

## Production Web

```powershell
.\gradlew.bat :webApp:jsBrowserProductionWebpack
$env:WEB_APP_BUNDLE = Join-Path $PWD 'webApp/build/kotlin-webpack/js/productionExecutable'
Push-Location tools/web-storage-tests
npm run test:app
Pop-Location
Remove-Item Env:WEB_APP_BUNDLE
```

Development и production собирайте отдельными Gradle-вызовами. Для hosting объедините bundle из указанного каталога со статическими ресурсами `webApp/build/processedResources/js/main`: index.html, browser-locale.js, Skiko и WASM. Сохраняйте имена; `.wasm` отдавайте как `application/wasm`, `/api` проксируйте на backend. Библиотека IndexedDB привязана к конечному origin. Hosting deployment, TLS, квоты и публичный доступ требуют отдельной настройки.

## Диагностика

| Симптом | Что проверить |
| --- | --- |
| Backend не стартует с ошибкой JWT secret | Значение в окружении запускающего терминала, минимум 32 символа |
| Import ERROR | yt-dlp/Node/ffmpeg/ffprobe в PATH, доступность видео и немецких субтитров, длительность и backend log |
| HTTP 503 при импорте | Заняты worker+queue slots; повторить после Retry-After, не спамить POST |
| 401 на AI/sync | Войти через Account, проверить срок JWT и смену secret; AI в UI также требует входа |
| TUI/desktop нет звука | Наличие native VLC либо ffplay, разрядность JVM/VLC, ограничения запуска ОС |
| Web пустой | Console/Network, загрузку browser-locale.js/skiko.js/WASM; проверить полный комплект ресурсов |
| Web «потерял» библиотеку | Точный origin/порт, профиль браузера, private mode; данные другого origin не исчезли |
| Локальное аудио отсутствует | Play online; SQLite не очищать, сохранить словарь/SRS |
| Windows batch: command line too long | Запустить installDist через `java -cp ".../lib/*"` как выше |
| Port already in use | Проверить владельца 8080/3000 и остановить свой прежний процесс; не завершать неизвестный PID |

В текущем Windows-окружении ffplay блокировался Application Control; обход политики не выполнялся. Android target отключён и `androidApp` не включён в settings: команды assemble APK сейчас не являются поддерживаемым сценарием.
