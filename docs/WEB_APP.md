# Web-приложение

Модуль `webApp` использует общий Compose-плеер, `PlayerViewModel`, репозитории и IndexedDB-хранилище из `shared`. Android SDK не нужен. Требуются JDK 21 и установленный Node.js 18+.

## Запуск

В первом терминале PowerShell из корня проекта:

```powershell
$env:PUBLIC_BASE_URL = 'http://127.0.0.1:3000'
.\gradlew.bat :backend:run
```

Во втором:

```powershell
.\gradlew.bat :webApp:jsBrowserDevelopmentRun
```

Откройте http://127.0.0.1:3000. Автоматическое открытие браузера отключено. Backend слушает порт 8080; dev server пересылает `/api` на `http://127.0.0.1:8080`. Таймаут прокси — 11 минут, поскольку извлечение видео может занять до 10 минут. Если меняете порт backend, измените `webApp/webpack.config.d/dev-server.js`.

`PUBLIC_BASE_URL` должен совпадать с origin страницы: аудио тогда скачивается через тот же прокси без CORS. Не заменяйте `127.0.0.1` на `localhost` только в одном месте: это разные origin и разные браузерные хранилища.

Для настоящего импорта установите `yt-dlp`, `ffmpeg` и `ffprobe` в PATH (или задайте `YT_DLP_EXECUTABLE`), используйте Node.js 22+. Windows-скрипт подготовки и результаты настоящего импорта/отмены описаны в [YOUTUBE_VERIFICATION.md](YOUTUBE_VERIFICATION.md). Обычный автоматический сценарий интерфейса использует тестовый API и WAV-файл; сетевые проверки запускаются отдельно.

## Сценарий

1. Вставьте ссылку на отдельное YouTube-видео с немецкими субтитрами и нажмите «Импортировать».
2. Дождитесь сохранения метаданных и аудио. Во время импорта доступна отмена; при ошибке ссылка остаётся для повторной попытки. Если метаданные уже сохранены, видео может остаться в библиотеке с сетевым аудио.
3. В плеере нажмите PLAY. Нажатие строки перематывает к её началу, нажатие слова открывает карточку.
4. Перевод пока демонстрационный (`MockLinguisticAssistant`), что указано над плеером. ADD TO DICTIONARY сохраняет слово с контекстом.
5. Откройте «Слова», чтобы увидеть слова для повторения. Перезагрузка страницы сохраняет библиотеку, аудио и слова.

URL вводится в нативное HTML-поле: оно поддерживает вставку, браузерную доступность и мобильную клавиатуру. Остальной интерфейс использует экспериментальный canvas API закреплённой версии Compose 1.5.10; полноценная доступность canvas-интерфейса и обновление Compose остаются отдельными задачами.

Сохранённое аудио воспроизводится из IndexedDB без обращения к backend. Для открытия самой страницы нужна доступная Web-сборка: service worker и установка PWA пока не реализованы. Хранилище привязано к origin и профилю браузера; его очистка удаляет локальные данные.

При уходе с экрана плеера освобождаются аудио, временный Blob URL и корутины. При закрытии страницы закрываются HTTP-клиент и worker базы; возврат из back/forward cache сохраняет подключение. Отмена импорта прерывает HTTP-запрос; dev proxy передаёт разрыв backend, где Netty отменяет обработчик и завершает дерево yt-dlp. Для production reverse proxy также нужна передача разрыва клиента upstream.

## Сборка и проверка

```powershell
.\gradlew.bat :webApp:jsBrowserDevelopmentWebpack
.\gradlew.bat :webApp:jsBrowserDistribution
```

Production-артефакты находятся в `webApp/build/dist/js/productionExecutable`. Раздавайте каталог целиком, включая `skiko.js`, WASM и worker-файлы. Настройте MIME `application/wasm` для `.wasm` и прокси `/api` на backend на том же HTTPS-origin. Для backend задайте этот публичный origin в `PUBLIC_BASE_URL`.

Браузерная проверка приложения:

```powershell
# Сначала соберите development bundle командой выше.
cd tools/web-storage-tests
npm ci
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PWD 'build/browser-tools'
npx playwright install chromium --only-shell
npm run test:app
```

Тест использует отдельный профиль Chromium, локальный тестовый HTTP API и настоящие репозитории/IndexedDB/аудиоплеер. Проверяются ошибка/повтор импорта, воспроизведение, сохранение слова, освобождение плеера, восстановление библиотеки и слова после перезагрузки, аудио при заблокированном API. Снимки экрана сохраняются в `tools/web-storage-tests/build/app`.

Для того же сценария на production-сборке задайте `WEB_APP_BUNDLE` перед `npm run test:app` (из каталога `tools/web-storage-tests`):

```powershell
$env:WEB_APP_BUNDLE = Join-Path $PWD '../../webApp/build/dist/js/productionExecutable'
npm run test:app
```

Development- и production-сборки прошли этот сценарий. Также прошли 39 JVM-тестов и 23 Kotlin-теста браузера. Проверен запуск `jsBrowserDevelopmentRun` с передачей `/api` через прокси. Команды регрессионных проверок хранилища описаны в [DATA_LAYER.md](DATA_LAYER.md).
