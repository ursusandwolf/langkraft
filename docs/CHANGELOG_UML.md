# REL-06 — 2026-10-22 — legacy recovery в изолированный профиль

```text
+-----------------------------------------+
| recover_legacy_profile <<Python module>> |
| audit_profile / clone_profile            |
+-------------------+---------------------+
                    | read-only SQL
                    v
+-------------------------+       backup snapshot      +------------------------+
| Original client profile |--------------------------->| Preparing profile      |
| DB + managed audio      |                            | DB + stable audio copy |
+-------------------------+                            +------------+-----------+
                                                                   | validate
                                                      +------------v-----------+
                                                      | New recovered profile  |
                                                      | rewritten paths/status |
                                                      | auth cleared + report  |
                                                      +------------------------+

RecoveryError <|-- UnavailableAudio
unknown ownership -> preserve original; repair status only inside isolated snapshot
failed preparation -> retain preparing directory; destination not published
```

Это операционная Python-утилита, а не новый слой Kotlin-приложения. SQLite backup и файловое копирование — адаптация инфраструктуры к recovery-сценарию; новые GoF-паттерны не вводились. Изоляция профилей заменяет недоказуемое определение смерти старого владельца. [Руководство](LEGACY_RECOVERY.md).

# REL-05 — 2026-10-22 — файловый Decorator и replay-журнал

```text
+---------------------------+             +----------------------------+
| AudioDownloaderImpl       |-----uses---->| AudioStorage <<Port>>      |
| SQL link -> confirmLinked |             +--------------+-------------+
+---------------------------+                            ^ implements
                                         +--------------+-------------+
+---------------------------+            | JournaledFileAudioStorage  |
| PendingAudioRecovery      |---uses----->| <<Decorator + Adapter>>    |
| free lease + ALL SQL refs  |   via       | pending sidecar / resolve  |
+---------------------------+ PendingPort +--------------+-------------+
                                                        | delegates
                                         +--------------v-------------+
                                         | FileAudioStorage           |
                                         | beforeWrite -> journal     |
                                         | stream -> .part -> .mp3    |
                                         +----------------------------+

native content lease -> force journal -> create .part -> publish MP3
SQL COMMIT -> confirmLinked -> remove journal
crash -> free lease -> SQL refs? keep + confirm : delete payload then journal
partial cleanup failure -> journal remains -> replay on next startup
```

JournaledFileAudioStorage реализует оба порта AudioStorage/PendingAudioStorage и декорирует потоковый FileAudioStorage. Журнал — долговечная запись незавершённой передачи владения. В IndexedDB используется транзакция, в файловой системе — проверяемое идемпотентное повторение с удалением журнала последним; распределённой транзакции с SQLite нет.

# REL-04 — 2026-10-22 — Desktop/TUI: platform lease

```text
+-------------------------+                  +--------------------------+
| AudioDownloaderImpl     |------uses-------->| AudioDownloadCoordinator |
| AudioDownloadRecovery   |                  | <<Platform Port>>        |
+------------+------------+                  +-------------+------------+
             | SQL + protocol                              ^ implements
             v                              +-------------+------------+
+-------------------------+                 | FileAudioDownloadCoordinator|
| DatabaseAccess          |                 | <<JVM Adapter>>          |
| Content + SyncMetadata  |                 | JVM claimed registry     |
+-------------------------+                 | FileChannel / FileLock   |
                                            +-------------+------------+
                                                          | owns
                                            +-------------v------------+
                                            | AudioDownloadLease       |
                                            | jvm-file-lock-v1         |
                                            | idempotent release       |
                                            +--------------------------+

DesktopModule -> same profile / download-locks -> downloader + DB startup recovery
active OS lock -> preserve DOWNLOADING
free OS lock + known SQL marker -> ERROR / COMPLETED with old path
```

Порт и общий Application Service сохранены; Java NIO — платформенный Adapter. Взаимное исключение внутри JVM дополняет кооперативный межпроцессный lock. Schema/Repository не изменялись. Уборка файлов после аварии потребует отдельного pending-журнала, текущий фикс восстанавливает SQL-статус.

# REL-03 — 2026-10-22 — pending-публикация и проверка SQL-ссылок

```text
+-------------------------+     after SQL COMMIT     +--------------------------+
| AudioDownloaderImpl     |------------------------->| AudioStorage             |
| <<Application Service>> |                          | confirmLinked(location)  |
+-------------------------+                          +-------------+------------+
                                                                  ^ implements
+-------------------------+     uses                 +-------------+------------+
| PendingAudioRecovery    |------------------------->| IndexedDbAudioStorage    |
| <<Application Service>> |                          | <<Platform Adapter>>     |
| recover()               |                          | pending list / resolve   |
+------------+------------+                          +-------------+------------+
             | uses                                               ^ implements
       +-----+-----------------------+               +-------------+------------+
       v                             v               | PendingAudioStorage      |
+---------------------+ +-------------------------+  | <<Recovery Port>>        |
| DatabaseAccess      | | AudioDownloadCoordinator|  +--------------------------+
| ALL Content refs    | | full content lease      |
+---------------------+ +-------------------------+

published pending -> SQL link -> confirmLinked -> confirmed (outside pending GC)
closed owner -> free lease -> SQL refs? yes: confirm / no: delete manifest+chunks
SQL read failed / active owner / unknown protocol -> preserve
```

Сохранены Ports/Adapters и компенсация. Долговечная pending-метка фиксирует незавершённую передачу владения между двумя хранилищами; это не распределённая транзакция. Проверка SQL проходит под DatabaseAccess и content lease; атомарное удаление ограничено IndexedDB. [Решение и точки аварии](ADR_AUDIO_DOWNLOAD_RECOVERY.md).

# REL-02 — 2026-10-22 — согласование SQL-статуса и владельца

```text
+-------------------------+      uses      +--------------------------------+
| AudioDownloaderImpl     |--------------->| AudioDownloadCoordinator       |
| <<Application Service>> |                | <<Platform Port>>              |
+------------+------------+                | tryAcquire(contentId) -> lease |
             |                            +---------------+----------------+
             | SQL + marker                               ^ implements
             v                            +---------------+----------------+
+-------------------------+               | WebAudioDownloadCoordinator    |
| DatabaseAccess          |<----------+    | <<Adapter>>                    |
| AppDatabase transaction |           |    | content Web Lock              |
| Content + SyncMetadata  |           |    +--------------------------------+
+-------------------------+           |          ^ uses
                                      |          |
                           +----------+----------+-------------+
                           | AudioDownloadRecovery              |
                           | <<Application Service>>            |
                           | free lease + known marker          |
                           | recheck SQL -> restore status      |
                           +------------------------------------+

lease held: DOWNLOADING + marker -> HTTP -> asset -> SQL link + remove marker
crash: free lease + marker -> ERROR (no old path) / COMPLETED (old path kept)
active lease / unknown marker / no coordination -> preserve
```

Ports/Adapters отделяют браузерную синхронизацию от общего downloader. UncoordinatedAudioDownloads сохраняет поведение платформ без реализации lease. Изменение статуса и метки образует одну SQLite-транзакцию; отдельная asset-блокировка REL-01 защищает manifest/chunks. [Решение и ограничения](ADR_AUDIO_DOWNLOAD_RECOVERY.md).

# REL-01 — 2026-10-22 — LK-03: lifetime lock и уборка

```text
+-------------------------+       calls       +---------------------------+
| IndexedDbAudioStorage   |------------------>| storage/index.js          |
| <<Platform Adapter>>    |                   | <<JS module>>             |
| store / delete          |                   | begin / append / commit   |
+-------------------------+                   | abort / recoverIncomplete |
                                              +-------------+-------------+
                                                            | coordinates
                                              +-------------v-------------+
                                              | Web Locks <<Browser API>> |
                                              | exclusive lock per asset  |
                                              +-------------+-------------+
                                                            | protects
                                              +-------------v-------------+
                                              | IndexedDB audio + chunks  |
                                              | <<Transactional Store>>   |
                                              +---------------------------+

begin -> hold lock -> append* -> commit/abort -> release lock
closed owner -> free lock -> recheck incomplete -> atomic manifest+chunks delete
active owner / complete / legacy / no-lock -> preserve
```

Порт/адаптер AudioStorage сохранён. Web Locks — механизм взаимного исключения владельца и уборщика; удаление manifest/chunks атомарно в пределах IndexedDB. Это не транзакция с SQLite. Уборка запускается из WebDatabaseFactory и beginAudio; SQL recovery и сбор готовых непривязанных объектов остаются отдельной задачей.

# INT-11 — 2026-10-22 — LK-02: передача владения и компенсация

```text
+--------------------------+         uses         +-------------------------+
| AudioDownloaderImpl      |--------------------->| AudioStorage <<Port>>   |
| <<Application Service>>  |                      | store / delete          |
| previous / location      |                      +------------+------------+
| linked                   |                                   ^ implements
+-------------+------------+                     +-------------+-------------+
              |                                  | FileAudioStorage          |
              | serialized by                    | IndexedDbAudioStorage     |
              v                                  | <<Platform Adapters>>     |
+--------------------------+                     +---------------------------+
| DatabaseAccess           |
| run under shared mutex   |      checks          +---------------------------+
|                         |--------------------->| AppDatabase (generated)   |
+--------------------------+                     | Content localAudioPath    |
                                                +---------------------------+

Ownership: storage --successful return--> downloader --SQL link--> database
Failure:   check persisted references --unbound only--> compensating delete
```

Порты/адаптеры и Repository сохранены; компенсация — механизм согласования двух хранилищ. Исключение возврата после COMMIT не отменяет владение БД. Crash recovery остаётся отдельной задачей. Полная текущая UML-карта: [UML_CLASSES](UML_CLASSES.md), паттерны и модули: [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md).

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
