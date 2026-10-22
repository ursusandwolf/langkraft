# INT-10 — 2026-10-22 — итоговая интеграция

```text
+-------------------------+       delegates       +---------------------------+
| MainApp / PlayerVM      |----------------------->| LinguisticAssistant       |
| <<Presentation Model>>  |                        | <<Strategy + Decorator>>  |
+------------+------------+                        +---------------------------+
             | uses
       +-----+------------------------+
       v                              v
+-----------------------+   +-----------------------------+
| AudioPlayer           |   | LocalContentRepository      |
| <<Bridge>>            |   | VocabularyRepository        |
| Web / VLC / ffplay    |   | <<Repository>>              |
+-----------------------+   +---------------+-------------+
                                            | implemented by
                                            v
                            +-----------------------------+
                            | SQLDelight + DatabaseAccess |
                            | SQLite JDBC / WASM worker   |
                            +-----------------------------+
```

Эти контракты сохранены при dev → main. Новых паттернов в завершающем коммите документации нет. Архитектура и границы проверок описаны в INTEGRATION_COMPLETION.md; незавершённые внешние проверки остаются в backlog.

# INT-09 — 2026-10-22

```text
+-------------------------+  normalizes  +-----------------------+
| browser-locale.js       |------------>| navigator locale tags |
| <<Compatibility Layer>> |             | POSIX -> BCP-47       |
+-------------+-----------+             +-----------------------+
              | before
              v
+-------------------------+
| Compose / Intl.Locale   |
+-------------------------+
```

Адаптер окружения выполняется до запуска Compose; новых доменных классов или GoF-паттернов не добавлено. Нормальные языковые предпочтения сохраняются, некорректные не останавливают рендеринг.

# INT-08 — 2026-10-22

```text
+----------------------+       depends on       +----------------------------+
| Common VM/Repository |----------------------->| coroutines-core 1.8.0      |
+----------------------+                        +-------------+--------------+
                                                            ^ same ABI
+----------------------+       uses             +-------------+--------------+
| Kotlin/JS regression |----------------------->| coroutines-test 1.8.0      |
| <<Test Fixture>>     |                        +----------------------------+
+----------------------+
```

Новых GoF-паттернов нет: исправлена бинарная совместимость тестового runtime с зависимостью Compose. Проверка работает в Chromium и CI.

# INT-07 — 2026-10-22

```text
+--------------------------+     delegates     +---------------------------+
| PlayerViewModel          |------------------>| PlayerLinguisticDelegate  |
| <<Presentation Model>>   |                   | selectionGeneration       |
| loadGeneration           |                   | wordJob / content epoch   |
| audioRequest / save guard|                   | dismiss / retry / reset   |
+------------+-------------+                   +-------------+-------------+
             | uses                                          | uses
             v                                               v
+--------------------------+                   +---------------------------+
| AudioPlayer <<Bridge>>   |                   | LinguisticAssistant       |
| load(url, requestId)     |                   | <<Strategy>>              |
| error(requestId, reason) |                   +---------------------------+
+------------+-------------+
             ^ implements
      +------+------------------+
      | Web / VLC / ffplay      |
      +-------------------------+

+--------------------------------------+     implements     +----------------------+
| SqlDelightVocabularyRepository        |------------------->| VocabularyRepository |
| <<Repository>>                       |                    +----------------------+
| reviewClock + SQL query observations |
| pending revision + acknowledgement   |
+--------------------------------------+
```

Делегирование сохраняет расширенные функции dev. Поколение запроса ограничивает область применения асинхронного результата; это механизм согласованности, а не отдельный GoF-паттерн. Контракт AudioPlayer отделяет платформенные движки, Repository — хранение и sync от UI.

# UML changelog

## INT-05 — 2026-10-22 — данные и Web

```text
+---------------------------+     +---------------------------+
| Content/Vocabulary Repo   |---->| DatabaseAccess            |
| Repository                |     | Mutex + async operations  |
+---------------------------+     +-------------+-------------+
                                                | owns access
                                  +-------------v-------------+
                                  | DatabaseHandle/AppDatabase|
                                  +-------------+-------------+
                                                | SqlDriver Adapter
                           +--------------------+------------------+
                           v                                       v
              +-------------------------+           +-------------------------+
              | PersistentJdbcDriver    |           | BrowserSqlDriver        |
              | owned JDBC connection   |           | Worker + SQLite WASM    |
              +-------------------------+           +-------------------------+

+---------------------------+     +---------------------------+
| AudioDownloaderImpl       |---->| <<AudioStorage>>          |
| response validation       |     | store / delete            |
+---------------------------+     +-------------+-------------+
                                  FileAudioStorage / IndexedDB

MainApp (common UI) --> platform DI --> Repository / AudioPlayer
```

Сохранены Repository и платформенные Adapter. Мигратор распознаёт схемы двух веток; Data Access сериализует работу соединения. Flow уведомляет UI, роли SRS не меняются.

## INT-06 — 2026-10-22 — auth и sync

```text
AccountView --> UserSession --> HttpClient (JWT)
                    |              |
                    v              v
               SyncMetadata    AuthService --> UserRepository

SyncManager --> VocabularyRepository --> SyncService
                 offline queue            |
                 revision ack              v
                               BackendVocabularyRepository
                               server cursor + tombstones
```

Сохраняется Repository; offline queue и tombstones реализуют надёжную передачу изменений. Теги сериализуются без потери запятых. Новая правка не подтверждается ответом на старый запрос. Общий UI использует Koin core без несовместимой Compose-обёртки.

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
