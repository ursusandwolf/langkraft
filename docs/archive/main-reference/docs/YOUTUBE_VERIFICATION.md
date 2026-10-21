# Проверка настоящего YouTube-импорта

Проверено 21 октября 2026 года на Windows, JDK 21, Node.js 24.20.0, yt-dlp 2026.08.19, FFmpeg/ffprobe 9.0.2 (Gyan essentials), Chromium 141 через Playwright 1.56.1. Android SDK не используется.

## Подготовка инструментов

```powershell
# Из корня проекта; устанавливает инструменты только в игнорируемый tools/media/build.
.\tools\media\Setup-MediaTools.ps1
$env:PATH = (Join-Path $PWD 'tools/media/build') + ';' + $env:PATH
$env:YT_DLP_EXECUTABLE = Join-Path $PWD 'tools/media/build/yt-dlp.exe'
$env:PUBLIC_BASE_URL = 'http://127.0.0.1:3000'
$env:DOWNLOADS_DIR = Join-Path $PWD 'tools/media/build/downloads'
.\gradlew.bat :backend:run
```

Скрипт скачивает закреплённые версии и проверяет SHA-256 перед установкой. Источники контрольных сумм: [yt-dlp 2026.08.19](https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/SHA2-256SUMS), [FFmpeg 9.0.2](https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.7z.sha256). Архив FFmpeg — около 34 MiB; распаковываются только `ffmpeg.exe` и `ffprobe.exe` (около 201 MiB вместе). Глобальный PATH не изменяется.

Установленный Node.js используется повторно: backend передаёт `--js-runtimes node`. Для импорта через Node нужен Node.js 22+; см. [официальные требования EJS](https://github.com/yt-dlp/yt-dlp/wiki/EJS). Официальный `yt-dlp.exe` уже включает EJS. Из-за `--ignore-config` пользовательская конфигурация yt-dlp не подхватывается. Скачивание удалённых EJS-компонентов и браузерные cookies не включены.

На других ОС установите yt-dlp, ffmpeg, ffprobe и поддерживаемый JavaScript runtime самостоятельно; Windows-скрипт там не применяется. Запуск интерфейса описан в [WEB_APP.md](WEB_APP.md).

## Положительный сценарий

Источник: [Saying Hello in Germany — Easy German 1](https://www.youtube.com/watch?v=_NXDM9XXhQ4), короткое видео с немецкой автоматической дорожкой.

| Проверка | Результат |
| --- | --- |
| Импорт из формы Web через Ktor и настоящий yt-dlp | HTTP 200 |
| Метаданные | Название и source URL соответствуют видео; длительность 156 секунд |
| Субтитры | 48 строк, все связаны с ID импортированного контента |
| Файл | Opus, 1 935 441 байт; codec подтверждён ffprobe |
| Длительность декодированного аудио | 156,2005 секунды; соответствует метаданным |
| Web-аудио | Сохранено в IndexedDB, воспроизводится через временный Blob URL |
| Перемотка | Нажатие первой строки возвращает аудио к началу этой строки |
| HTTP Range | `Range: bytes=0-63` → 206, 64 байта, `Content-Range: bytes 0-63/1935441` |

Проверка выполнялась на production-сборке, локальный HTTP-сервер передавал `/api` настоящему backend. Пример повтора (backend уже запущен, порт 3000 свободен):

```powershell
# Сначала из корня:
.\gradlew.bat :webApp:jsBrowserDistribution
cd tools/web-storage-tests
npm ci
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PWD 'build/browser-tools'
npx playwright install chromium --only-shell
$env:LIVE_YOUTUBE_URL = 'https://www.youtube.com/watch?v=_NXDM9XXhQ4'
$env:FFPROBE_EXECUTABLE = Join-Path $PWD '../media/build/ffprobe.exe'
npm run test:youtube
```

Это опциональная сетевая проверка. Она импортирует видео заново и сохраняет результат в backend-хранилище. `test:youtube` сам запускает сервер на порту 3000; одновременно dev server запускать не нужно. Отчёт, ответ API, Opus и снимок плеера сохраняются в `tools/web-storage-tests/build/youtube-live` и не коммитятся.

## Ошибки, отмена и таймаут

| Сценарий | Проверка и результат |
| --- | --- |
| Неверный URL `https://example.com/video` | Настоящий HTTP-запрос → 400 до запуска yt-dlp |
| Недоступное видео `aaaaaaaaaaa` | Настоящий HTTP-запрос → 502; набор файлов не изменился |
| Нет субтитров: [Black Screen 10 seconds](https://www.youtube.com/watch?v=W9nZ6u15yis) | CLI подтвердил отсутствие обычных и автоматических субтитров; backend → 502, частичных файлов нет |
| Отмена из Web | Нажатие «Отменить» после запуска настоящего yt-dlp: процесс остановлен, журнал удалён, набор файлов не изменился |
| Разрыв соединения | Автотест с настоящим Netty и HTTP/1.1-клиентом подтверждает отмену обработчика и удаление частичного файла |
| Таймаут процесса | Автотест запускает настоящий Java-процесс с лимитом 2 секунды: ошибка таймаута, процесс остановлен, журнал удалён |
| Ненулевой exit code | Настоящий дочерний процесс с кодом 7 → `IngestionException` |

Время ожидания yt-dlp в production осталось 600 секунд. Полный десятиминутный таймаут на YouTube намеренно не выжидался: механизм проверен на управляемом дочернем процессе без сети. Сетевые ошибки клиент получает как общее сообщение об ошибке импорта; HTTP 502 объединяет отсутствие субтитров и ошибки извлечения.

Проверка кнопки отмены использует настоящий Web dev server. Запустите его во втором терминале из корня:

```powershell
.\gradlew.bat :webApp:jsBrowserDevelopmentRun
```

Затем в третьем терминале:

```powershell
cd tools/web-storage-tests
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $PWD 'build/browser-tools'
$env:LIVE_YOUTUBE_URL = 'https://www.youtube.com/watch?v=_NXDM9XXhQ4'
# Тот же каталог, который передан backend через DOWNLOADS_DIR:
$env:LIVE_DOWNLOADS_DIR = Join-Path $PWD '../media/build/downloads'
npm run test:youtube:cancel
```

Тест рассчитан на локальный backend без параллельных импортов: он сравнивает список файлов и наблюдает временный журнал. Он не удаляет существующие загрузки.

## Исправления по результатам проверки

- Включён Node runtime для актуального yt-dlp.
- Ktor 2 продолжал обработку после ухода клиента. Для импорта добавлена отмена по закрытию Netty-канала; listener удаляется после завершения операции.
- Dev proxy не обрывал upstream после отмены уже отправленного POST. Теперь закрытие незавершённого ответа закрывает запрос к backend. Production reverse proxy также должен передавать разрыв клиента upstream; при его буферизации или удержании запроса мгновенная отмена не гарантируется.
- Завершение дерева процессов и очистка журнала выполняются в `NonCancellable` на IO. На Windows предусмотрено короткое ограниченное ожидание освобождения файловых дескрипторов.

## Ограничения

Доступность видео, формат ответа YouTube и сетевые ограничения меняются. Сетевые проверки отделены от обычного `npm test` и Gradle-тестов, не требуют AI-ключей или входа в YouTube. При изменении инструментов повторите проверку; закрепление версий обеспечивает воспроизводимую установку, но не вечную совместимость с YouTube.

Автоматические субтитры содержат ошибки распознавания, особенно во вступлении этого видео. Проверена техническая согласованность дорожки, таймингов и аудио; лингвистическая точность ASR не гарантируется. Спринт 5 добавил валидацию SRT и регрессионные проверки преобразованного VTT; правила и отдельная офлайн-проверка FFmpeg описаны в [SUBTITLES.md](SUBTITLES.md). Сетевой импорт после этого изменения не повторялся. Все браузерные проверки выполнялись в Chromium; другие браузеры ещё не проверялись.
