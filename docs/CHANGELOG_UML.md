# UML changelog

## INT-04 — 2026-10-21 — аудио, отмена и ограниченный импорт

```text
+-------------------------+          +-------------------------+
| YouTubeIngestionService |--------->| IngestionJobRepository  |
| startIngestion / close  |          | Repository              |
| bounded admission      |          +-------------------------+
| Channel + workers      |
+--------+----------------+
         | uses                             uses
         +-----------------------------+---------------------+
         v                             v                     v
+------------------+        +--------------------+  +-------------------+
| YtdlpClient      |        | WaveformExtractor  |  | SrtParser         |
| CLI Adapter      |        | extract: 200 bins  |  | SRT/VTT validation|
+--------+---------+        +----------+---------+  +-------------------+
         | uses                        | uses
         +--------------+--------------+
                        v
              +------------------------+
              | <<ProcessRunner>>      |
              | execute(args): String  |
              +-----------+------------+
                          ^ implements
              +-----------+------------+
              | ExecutableProcessRunner|
              | timeout / cancel / reap|
              +-----------+------------+
                          | ProcessBuilder Adapter
                          v
                    yt-dlp / ffmpeg

+----------------------+    +-----------------------+
| AudioDownloaderImpl  |--->| HttpClient / FileSystem|
| HTTP/MIME/size guard |    +-----------------------+
| cancellation cleanup|
+----------------------+
```

Сохраняется Repository, CLI доступ оформлен Adapter с заменяемым ProcessRunner для испытаний. Очередь с воркерами реализует Producer–Consumer (конкурентный шаблон, не новый GoF-класс). CancellationException пробрасывается через use case, sync и offline delegate. Схема БД, SRS и протокол синхронизации в INT-04 не менялись.

## INT-03 — 2026-10-21 — платформенный dispatcher и baseline

```text
+----------------------+       +-----------------------------+
| SyncManager          |------>| ioDispatcher (expect val)   |
| sync(force)          |       +--------------+--------------+
+----------+-----------+                      | actual
           | uses                   +---------+----------+
           v                        |                    |
+----------------------+   +--------v---------+ +--------v---------+
| VocabularyRepository |   | Desktop/Android  | | JS               |
| Repository pattern   |   | Dispatchers.IO   | | Dispatchers.Default |
+----------------------+   +------------------+ +------------------+

+----------------------+       +-----------------------------+
| PlayerViewModel      |------>| kotlinx.datetime.Clock      |
| scroll timeout       |       | System.now()                |
+----------------------+       +-----------------------------+
```

Сохраняется Repository; платформенные детали вынесены через существующий механизм expect/actual. Это свойство, а не новый класс или отдельный GoF-шаблон. Тестовые репозитории используют отдельные временные SQLite-файлы на каждый тест. Production-схема и контракты Repository не изменены.

## INT-02 — 2026-10-21 — статус проекта в README

Изменена только документация. Диаграмма классов и применяемые Repository/Adapter остаются такими, как зафиксировано в INT-01; новые классы и шаблоны не добавлялись. README связывает найденные проблемы Web DI/хранилища, импорта и репозиториев с этапами стабилизации.

## INT-01 — 2026-10-21 — аудит интеграции

Зафиксирована существующая структура dev; реализация классов не менялась. План интеграции: `INTEGRATION_PLAN_2026-10-21.md`.

```text
+---------------------------+       +-------------------------+
| YouTubeIngestionService   |------>| IngestionJobRepository  |
| startIngestion(url): id   |       | save / update / cleanup |
+-------------+-------------+       +-------------------------+
              | uses                        Repository
              v
+---------------------------+
| YtdlpClient               |  Adapter к внешнему CLI
| getVideoInfo / download   |
+---------------------------+

+---------------------------+       +-------------------------+
| PlayerViewModel           |------>| <<AudioPlayer>>         |
| presentation state       |       +------------+------------+
+---------------------------+                    ^ implements
                                    +------------+------------+
                                    | VLC / FFmpeg / Browser   |
                                    +-------------------------+
                                     платформенные реализации

+---------------------------+       +-------------------------+
| LocalContentRepository    |<......| SqlDelightContentRepo   |
+---------------------------+       +------------+------------+
             Repository                          | uses
                                                 v
                                    +-------------------------+
                                    | AppDatabase             |
                                    +-------------------------+
```

Сохраняются Repository и Adapter, внедрение зависимостей Koin. Разные реализации AudioPlayer используют общий интерфейс; выбор зависит от платформы. Планируемые ограничения очереди, миграции и async Web-доступ ещё не реализованы и не обозначаются как выполненные шаблоны.
