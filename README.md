# Langkraft 🇩🇪

Приложение для изучения немецкого через прослушивание: импортируйте аудио и немецкие субтитры из YouTube, слушайте, переходите по репликам и сохраняйте слова с контекстом.

Первая платформа — **Web**. Android отложен до финального этапа. Перевод пока демонстрационный; Gemini API, полноценное SRS-обучение и PWA ещё не подключены.

## Требования к окружению

| Инструмент | Версия / назначение | Когда нужен |
| --- | --- | --- |
| Git | Получение полного репозитория | Для разработки из clone |
| JDK | **21**, включая `java` и `javac` | Сборка и backend |
| Node.js и npm | **Node.js 22+**; CI использует Node 22 | Web-сборка и runtime для yt-dlp |
| Браузер | Локально проверен Chromium | Web-интерфейс |
| yt-dlp | Исполняемый файл | Настоящий импорт YouTube |
| FFmpeg | `ffmpeg` и `ffprobe` | Конвертация аудио/субтитров и проверка медиа |
| Chromium для Playwright | Установка отдельной командой ниже | Браузерные автотесты |
| Android SDK | Не нужен для Web | Только отложенная Android-цель |

JDK и Node.js должны быть в `PATH`. Если задан `JAVA_HOME`, он должен указывать на JDK 21. Для свежего окружения используйте Node.js 22, как в CI; локальные проверки также проходили с Node.js 24.20.0.

Отдельно устанавливать Gradle, Yarn или сервер базы данных не нужно. Wrapper скачивает **Gradle 8.14**; Gradle управляет Yarn и зависимостями Web-приложения. SQLite работает внутри приложения/браузерного worker. Первый запуск требует сети для скачивания зависимостей.

Проверьте окружение:

```shell
java -version
javac -version
node --version
npm --version
```

## Библиотеки и зависимости

Это версии, закреплённые в проекте, а не список последних релизов.

| Компонент | Версия | Назначение |
| --- | --- | --- |
| Kotlin / Kotlin Multiplatform | 1.9.20 | Общая логика, JVM и JS |
| Compose Multiplatform | 1.5.10 | Web canvas UI и общий плеер |
| Ktor | 2.3.5 | Netty backend, HTTP-клиенты, JSON, Range |
| kotlinx.coroutines | 1.7.3 | Flow, корутины и отмена |
| kotlinx.serialization JSON | 1.6.0 | Модели и API |
| SQLDelight | 2.0.0 | Генерация запросов и SQLite-драйверы |
| wa-sqlite | 1.0.0 | SQLite/WASM с IndexedDB VFS |
| Media3 ExoPlayer | 1.1.1 | Android-адаптер плеера |
| Playwright | 1.56.1 | Браузерные проверки |
| Webpack / webpack-cli | 5.112.0 / 5.1.4 | Отдельный инструментарий браузерных тестов |

Источники версий: [корневой Gradle-файл](build.gradle.kts), [shared](shared/build.gradle.kts), [backend](backend/build.gradle.kts), [webApp](webApp/build.gradle.kts), [Web storage](shared/src/jsMain/storage/package.json), [тестовый пакет](tools/web-storage-tests/package.json). Webpack самого приложения управляется Kotlin/JS toolchain.

Gradle скачивает библиотеки автоматически. `npm ci` вручную нужен только в `tools/web-storage-tests` для автотестов. В корне проекта `npm install` запускать не требуется. Ключ Gemini для текущего MVP не нужен.

## Получение проекта

```shell
git clone https://github.com/ursusandwolf/langkraft.git
cd langkraft
```

Команды ниже выполняются из корня `langkraft`, если не указан другой каталог. Windows: PowerShell и `.\gradlew.bat`. Linux/macOS: `bash ./gradlew`, что работает и без executable-бита у Wrapper.

Для запуска из ZIP откройте корневой каталог распакованного проекта. Архив для ревью с пометкой `no-tests` намеренно исключает тесты и их инструменты: команды полного CI для него недоступны.

## Запуск на Windows

### 1. Подготовить импорт YouTube

В терминале backend, из корня проекта:

```powershell
.\tools\media\Setup-MediaTools.ps1
$env:PATH = (Join-Path $PWD 'tools/media/build') + ';' + $env:PATH
$env:YT_DLP_EXECUTABLE = Join-Path $PWD 'tools/media/build/yt-dlp.exe'
yt-dlp --version
ffmpeg -version
ffprobe -version
```

Скрипт использует `curl.exe` и `tar.exe`, скачивает закреплённые инструменты в игнорируемый `tools/media/build` и проверяет SHA-256. Глобальный `PATH` он не меняет. Присваивания `$env:...` действуют в текущем терминале; повторяйте их при открытии нового терминала backend. Если PowerShell блокирует скрипт, используйте установленные media tools или принятую в вашей среде политику выполнения.

Если инструменты уже есть в `PATH`, шаг можно пропустить. `YT_DLP_EXECUTABLE` задаёт отдельный путь к yt-dlp; FFmpeg и Node.js должны быть в `PATH`. Без media tools можно собрать и открыть интерфейс, но импорт завершится ошибкой.

### 2. Запустить backend

В том же терминале:

```powershell
$env:PUBLIC_BASE_URL = 'http://127.0.0.1:3000'
$env:MAX_CONCURRENT_IMPORTS = '2'
.\gradlew.bat :backend:run
```

Backend слушает порт **8080**. Импорт может занять несколько минут; таймаут yt-dlp — десять минут.

### 3. Запустить Web

Во втором терминале, из корня проекта:

```powershell
.\gradlew.bat :webApp:jsBrowserDevelopmentRun
```

Откройте **http://127.0.0.1:3000** вручную. Dev server пересылает `/api` на backend:8080. Оба терминала должны оставаться открытыми; для остановки используйте `Ctrl+C`.

## Запуск на Linux/macOS

Установите JDK 21, Node.js 22+, yt-dlp, ffmpeg и ffprobe средствами своей ОС. Проверьте `yt-dlp --version`, `ffmpeg -version`, `ffprobe -version`. Windows setup-скрипт здесь не используется. Для извлечения через Node runtime установка yt-dlp должна предоставлять необходимые EJS-компоненты; детали — в [инструкции импорта](docs/YOUTUBE_VERIFICATION.md).

Терминал 1, корень проекта:

```shell
PUBLIC_BASE_URL=http://127.0.0.1:3000 MAX_CONCURRENT_IMPORTS=2 bash ./gradlew :backend:run
```

Терминал 2, корень проекта:

```shell
bash ./gradlew :webApp:jsBrowserDevelopmentRun
```

Откройте **http://127.0.0.1:3000**. При необходимости задайте `YT_DLP_EXECUTABLE=/absolute/path/to/yt-dlp` в окружении backend.

## Первый пользовательский сценарий

1. Вставьте HTTPS-ссылку на отдельное YouTube-видео с немецкими субтитрами; плейлисты не поддерживаются.
2. Нажмите «Импортировать» и дождитесь сохранения аудио. Во время импорта доступна отмена.
3. Нажмите PLAY. Нажатие строки перематывает аудио; нажатие слова открывает карточку перевода.
4. Нажмите ADD TO DICTIONARY и откройте «Слова».
5. Перезагрузите страницу: библиотека, аудио и слова сохраняются в том же профиле браузера.

Перевод обозначен как демонстрационный. Сохранённое аудио играет без backend в открытом приложении; для загрузки самой страницы Web-сервер пока нужен. Service worker/PWA не реализованы.

Если контент остался в библиотеке, но его локальный аудиообъект потерян, плеер предложит «Воспроизвести по сети». Нажатие запускает доступный remote URL без изменения сохранённой локальной ссылки. Ошибка remote не приводит к циклу запросов; RETRY повторяет сетевой источник. Автоматического переключения при временном сбое storage или запрете autoplay нет.

## Конфигурация и данные

Переменные окружения backend:

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `PORT` | `8080` | Порт backend |
| `PUBLIC_BASE_URL` | `http://localhost:<PORT>` | Origin ссылок на аудио; для Web development задайте `http://127.0.0.1:3000` |
| `DOWNLOADS_DIR` | `downloads` | Каталог медиа относительно рабочего каталога backend; для точного расположения задайте абсолютный путь |
| `YT_DLP_EXECUTABLE` | `yt-dlp` из `PATH` | Путь к исполняемому файлу |
| `MAX_CONCURRENT_IMPORTS` | `2` | Положительное целое: активные импорты на экземпляр backend |

Backend читает окружение процесса; `.env` автоматически не загружается. Пример явного каталога медиа в PowerShell перед запуском backend:

```powershell
$env:DOWNLOADS_DIR = Join-Path $PWD 'tools/media/build/downloads'
```

При смене `PORT` измените также target в [dev-server.js](webApp/webpack.config.d/dev-server.js): один `PORT` не перенастраивает Web proxy. `PUBLIC_BASE_URL` должен совпадать с origin страницы. `localhost:3000` и `127.0.0.1:3000` имеют разные IndexedDB-хранилища.

SQLite и аудио Web-приложения хранятся в IndexedDB браузера, отдельно от медиа backend. Очистка данных сайта удаляет локальную библиотеку и слова. Схема 2 мигрирует старую БД с сохранением ссылок слов и SRS; порядок одинаковых начал у новых реплик хранится в `ordinal`. Подробнее — в [DATA_LAYER.md](docs/DATA_LAYER.md).

## API backend

| Запрос | Результат |
| --- | --- |
| `POST /api/ingest`, JSON `{"url":"https://www.youtube.com/watch?v=VIDEO_ID"}` | Метаданные, субтитры и абсолютный URL Opus |
| `GET /api/media/{id}.opus` | Аудио с Range: 200 либо 206; неизвестный файл — 404 |

Импорт возвращает 400 для неподдерживаемой ссылки, 502 при ошибке извлечения/субтитров, 503 при занятом лимите. Очереди нет; повторите запрос позже. Некорректные субтитры отклоняют импорт целиком, его файлы удаляются. Правила SRT/VTT — в [SUBTITLES.md](docs/SUBTITLES.md).

## Сборка

PowerShell, корень проекта:

```powershell
# Компиляция без запуска тестов.
.\gradlew.bat :shared:compileKotlinDesktop :backend:classes :shared:compileKotlinJs

# Development-сборка.
.\gradlew.bat :webApp:jsBrowserDevelopmentWebpack

# Production-сборка: отдельный вызов Gradle.
.\gradlew.bat :webApp:jsBrowserDistribution
```

Linux/macOS: замените `.\gradlew.bat` на `bash ./gradlew`. Development и production собирайте отдельными вызовами: Kotlin 1.9 использует общий каталог вывода compileSync.

Production-файлы: `webApp/build/dist/js/productionExecutable`. Раздавайте весь каталог, включая `skiko.js`, worker и WASM; MIME для `.wasm` — `application/wasm`. На одном HTTPS-origin настройте reverse proxy `/api` к backend, задайте этот origin в `PUBLIC_BASE_URL`. Proxy должен допускать долгие импорты и передавать отключение клиента upstream для отмены. Production-хостинг пока не настроен; подробнее — [WEB_APP.md](docs/WEB_APP.md).

## Проверки

Эти команды предназначены для **полного репозитория**. Обычные тесты используют локальные fixtures, не обращаются к YouTube и не требуют AI-ключей. Установка зависимостей требует сети.

JVM-регрессия, PowerShell из корня:

```powershell
.\gradlew.bat :shared:desktopTest :backend:test
```

### Браузерные проверки на Windows

Начните из корня проекта и выполняйте команды последовательно. Backend и dev server не нужны: тесты поднимают собственные HTTP-серверы.

```powershell
Push-Location tools/web-storage-tests
npm ci
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PWD 'build/browser-tools'
npx playwright install chromium --only-shell
Pop-Location

$env:CHROME_BIN = Join-Path $env:PLAYWRIGHT_BROWSERS_PATH 'chromium_headless_shell-1194/chrome-win/headless_shell.exe'
.\gradlew.bat :shared:jsBrowserTest
.\gradlew.bat :webApp:jsBrowserDevelopmentWebpack

Push-Location tools/web-storage-tests
npm test
$env:WEB_APP_ARTIFACTS = 'build/app-development'
# Удалить значение от прошлого production-прогона, если оно было задано.
Remove-Item Env:WEB_APP_BUNDLE -ErrorAction SilentlyContinue
npm run test:app
Pop-Location

.\gradlew.bat :webApp:jsBrowserDistribution
Push-Location tools/web-storage-tests
$env:WEB_APP_BUNDLE = '../../webApp/build/dist/js/productionExecutable'
$env:WEB_APP_ARTIFACTS = 'build/app-production'
npm run test:app
Pop-Location
```

Путь `chromium_headless_shell-1194` соответствует закреплённому Playwright 1.56.1; при обновлении Playwright проверьте имя каталога в `build/browser-tools`.

### Браузерные проверки на Linux/macOS

Из корня проекта:

```shell
cd tools/web-storage-tests
npm ci
npx playwright install --with-deps chromium
export CHROME_BIN="$(node --input-type=module -e 'import { chromium } from "playwright"; console.log(chromium.executablePath())')"
cd ../..
bash ./gradlew :shared:jsBrowserTest
bash ./gradlew :webApp:jsBrowserDevelopmentWebpack
cd tools/web-storage-tests
npm test
unset WEB_APP_BUNDLE
WEB_APP_ARTIFACTS=build/app-development npm run test:app
cd ../..
bash ./gradlew :webApp:jsBrowserDistribution
cd tools/web-storage-tests
WEB_APP_BUNDLE=../../webApp/build/dist/js/productionExecutable WEB_APP_ARTIFACTS=build/app-production npm run test:app
cd ../..
```

HTML/XML-отчёты: `shared/build/reports/tests/desktopTest`, `shared/build/reports/tests/jsBrowserTest`, `backend/build/reports/tests/test`. Снимки приложения: `tools/web-storage-tests/build/app-development` и `tools/web-storage-tests/build/app-production`. Проверяется также миграция БД 1 → 2 и повторное открытие версии 2. Настоящий импорт и его отмена проверяются отдельно по [YOUTUBE_VERIFICATION.md](docs/YOUTUBE_VERIFICATION.md).

## Continuous integration

[GitHub Actions](.github/workflows/ci.yml) запускается на PR, push в `main` и вручную. Среда: Ubuntu 24.04, JDK 21, Node.js 22. Отдельные jobs проверяют JVM и Web: Kotlin-тесты в Chromium, хранилище, development/production-сборки и сценарии приложения. Используются кэши Gradle/npm; отчёты и screenshots сохраняются на 14 дней. Успешная Web-проверка публикует артефакт `web-production`.

Последняя локальная проверка: 75 JVM-тестов, 34 Kotlin-теста браузера, три сценария хранилища и сценарии приложения/миграции на обеих сборках. Первый hosted JVM job прошёл; Web job упал на несовместимом с npm 10 lock-файле. Исправление зависимостей проверено локально, успешный hosted rerun ещё не подтверждён.

## Если запуск не работает

| Симптом | Что проверить |
| --- | --- |
| Wrapper не находит Java / выбрана другая Java | `java -version`, `javac -version`, `JAVA_HOME`; используйте JDK 21 |
| Gradle не может создать lock-файл в кэше | Доступ на запись в `GRADLE_USER_HOME`; при необходимости задайте доступный локальный каталог |
| `node` не найден | Node.js должен быть в `PATH` терминала Gradle/backend; автоматическое скачивание Node отключено |
| Страница открывается, но импорт не работает | Запущен ли backend:8080; доступны ли yt-dlp/FFmpeg/Node в его окружении |
| API возвращает 502 | Доступность видео, немецкая дорожка, media tools и журнал backend; клиент получает общее сообщение |
| API возвращает 503 | Заняты слоты импорта; дождитесь завершения либо измените положительный лимит |
| Аудио скачивается с неверного адреса / proxy не подключается | Согласованность `PUBLIC_BASE_URL`, origin страницы и порта/target proxy |
| После смены адреса «пропала» библиотека | Вернитесь к прежнему origin и профилю браузера |
| Playwright не находит браузер | Повторите установку с тем же `PLAYWRIGHT_BROWSERS_PATH`, что используется в тестах |
| `npm ci` отклоняет lock-файл | Используйте `package.json` и `package-lock.json` из одной версии репозитория; не обходите проверку через `--force` |
| Порт занят | Освободите 8080/3000 либо согласованно измените конфигурацию backend/proxy |

## Структура и дальнейшая работа

- `shared` — domain-модели и use cases, репозитории, SQLDelight, Compose UI, платформенные адаптеры.
- `backend` — Ktor API, yt-dlp, SRT и выдача медиа.
- `webApp` — точка входа Web, связывание зависимостей и dev proxy.
- `tools/media` — установка media tools на Windows.
- `tools/web-storage-tests` — Playwright, fixtures и проверки Web.
- `docs` — [контекст](docs/PROJECT_CONTEXT.md), [roadmap](docs/todo.md), [предварительное ревью](docs/CODE_REVIEW_2026-10-21.md).

История выполненных изменений и проверок: [CHANGELOG](CHANGELOG.md). Изменения классов и используемые паттерны отражаются под теми же ID в [текстовом UML-ченджлоге](docs/CHANGELOG_UML.md).

Открыты интеграция Gemini, SRS-тренировка, доступность canvas UI, PWA и авторизация. Лимит параллельности не ограничивает размер загрузки и общий объём downloads; квоты и очистка старых файлов пока не реализованы. Другие замечания ревью — в отчёте.

Android пока содержит адаптеры общего модуля, отдельного Android-приложения нет. Для отложенной проверки нужны Android SDK Platform 34 / Build Tools 34.0.0 и `ANDROID_HOME` либо `sdk.dir` в `local.properties`:

```powershell
.\gradlew.bat -PenableAndroid=true :shared:compileDebugKotlinAndroid
```

Android-сборка не подтверждена и не требуется для разработки Web.
