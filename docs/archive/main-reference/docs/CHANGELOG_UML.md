# UML-ченджлог классов и паттернов

Парный журнал для [CHANGELOG.md](../CHANGELOG.md). Новая запись использует тот же ID и дату, показывает затронутые классы, изменение методов/связей и роль существующих паттернов. Диаграммы — текстовая псевдографика в моноширинном блоке, без необходимости Mermaid/PlantUML. UML не заменяет описание поведения и результаты тестов в основном журнале. Чисто документационные изменения фиксируются без вымышленных изменений классов.

Обозначения: `-->` — использует/ассоциация; `..>` — зависимость, в том числе исключение; `<|..` — реализация интерфейса; `<<...>>` — роль. Названия Repository, Adapter и Strategy описывают роли существующих классов, а не новые фреймворки.

## LK-05

Дата: 2026-10-21. Статус: Unreleased. [Поведение и проверки](../CHANGELOG.md#unreleased).

Вместо строки ошибки передаётся типизированное состояние с ID источника; решение о ручном fallback принадлежит ViewModel, а не storage. UI отражает состояние и отправляет событие пользователя.

```text
+----------------------------------------+
| ImmersionPlayerView <<View / MVVM>>     |
|----------------------------------------|
| observes PlayerState                  |
| button: PlayOnline [NEW]               |
+----------------------------------------+
              | event                    ^ state
              v                          |
+----------------------------------------+      +--------------------------------+
| PlayerViewModel <<ViewModel / MVVM>>    |----->| PlayerState                    |
|----------------------------------------|      |--------------------------------|
| - activeAudioRequest: Long? [NEW]      |      | + canPlayOnline: Boolean [NEW] |
| - startAudio(content, useRemote) [NEW] |      | + isUsingRemoteAudio [NEW]     |
| - playOnline() [NEW]                   |      +--------------------------------+
| + retryLoad(): retain selected source |
+----------------------------------------+
              | load(url, requestId)       ^ error StateFlow
              v                          |
+----------------------------------------+      +--------------------------------+
| <<interface>> AudioPlayer              |..>   | AudioPlayerError [NEW]         |
|----------------------------------------|      |--------------------------------|
| + error: StateFlow<AudioPlayerError?>  |      | + requestId: Long             |
| + load(url, requestId = 0) [CHANGED]    |      | + reason: AudioErrorReason    |
| + play() / pause() / release()         |      | + message: String             |
+----------------------------------------+      +--------------------------------+

AudioPlayer <|.. AudioPlayerImpl (JS)
AudioPlayer <|.. AudioPlayerImpl (Android)

+----------------------------------------+
| AudioPlayerImpl (JS) <<platform Adapter>>|
|----------------------------------------|
| source generation                     |
| per-load HTMLAudioElement              |
+----------------------------------------+
              |
              +--> readAudioBlob()
                       |
                       +..> LocalAudioUnavailableError (JS) [NEW]
                            -> mapped to LOCAL_ASSET_UNAVAILABLE

+----------------------------------------+
| <<enum>> AudioErrorReason [NEW]         |
|----------------------------------------|
| LOCAL_ASSET_UNAVAILABLE                |
| STORAGE_ERROR                         |
| SOURCE_ERROR                          |
| AUTOPLAY_BLOCKED                      |
+----------------------------------------+
```

MVVM, платформенный Adapter и взаимозаменяемые реализации `AudioPlayer` существовали раньше. Изменение уточняет контракт и жизненный цикл; новые паттерны ради одной ветки fallback не добавлялись. Схема SQLite, `ContentRepository` и локальные ссылки не меняются. Временная недоступность хранилища и отсутствие объекта разделены; текст ошибки не определяет поведение. Отложенная Android-цель обновлена статически, её сборка не проверена.

## LK-06

Дата: 2026-10-21. Статус: Unreleased. [Поведение и проверки](../CHANGELOG.md#unreleased).

До изменения `ContentApi.download` проверял только HTTP-статус и передавал тело storage. После проверки статуса добавлен guard MIME; несовместимый тип вызывает отдельное исключение до сохранения.

```text
+--------------------------------------------+
| SqlDelightContentRepository <<Repository>>  |
|--------------------------------------------|
| - api: ContentApi                          |
| - audioStorage: AudioStorage               |
| + downloadAudio(content): String           |
+--------------------------------------------+
             | uses
             v
+--------------------------------------------+        +-------------------------+
| ContentApi <<HTTP Adapter>>                 |------->| HttpClient              |
|--------------------------------------------|        +-------------------------+
| + ingest(url): ImmersionContent            |
| + download(content, storage): String       |
| - checkResponse(response)                  |
| - checkAudioContentType(response) [NEW]    |
+--------------------------------------------+
       | ..> throws on incompatible MIME
       v
+--------------------------------------------+
| UnexpectedAudioContentTypeException [NEW]   |
|--------------------------------------------|
| + mediaType: String                        |
+--------------------------------------------+

ContentApi --store()--> +------------------------------------------+
                       | <<interface>> AudioStorage [Strategy port]|
                       |------------------------------------------|
                       | + store(contentId, source, bytes): String |
                       +------------------------------------------+
                                          ^
                                          | <|.. realizes
                      +-------------------+-------------------+
                      |                                       |
        +---------------------------+           +---------------------------+
        | FileAudioStorage          |           | IndexedDbAudioStorage     |
        +---------------------------+           +---------------------------+

Ktor media route --> LocalFileContent(file, audio/ogg)
                           |
                           +--> PartialContent --> HTTP 200 / Range 206
```

Repository сохраняет предметный контракт, HTTP Adapter проверяет ответ транспорта, реализации стратегии хранения не получают явно неподходящий ответ. Зависимости внедряются через конструкторы; отдельные Factory/Decorator/Chain of Responsibility для одной проверки не добавлялись. Схема БД и интерфейс `AudioStorage` не менялись.

## DOC-01

Дата: 2026-10-21. Статус: Unreleased. README, инструкция AI, входящее ревью, план исправлений и спринты 6–9. Классы, методы и паттерны этой записью не меняются.

```text
[README / Review / Sprint plan / Changelog]
                    |
                    +--> documents existing classes; no class delta
```

## UI-01

Дата: 2026-10-21. Проверка текущих связей через запущенное приложение, без изменения классов. [Результаты](../CHANGELOG.md#unreleased).

```text
+-------------------------+      +----------------------------------+
| LibraryApp (Compose UI)  |----->| IngestContentUseCase             |
+-------------------------+      +----------------------------------+
                                              |
                                              v
                                  [ContentRepository <<Repository>>]
                                              |
                                              v
                                  [ContentApi <<HTTP Adapter>>]
                                              |
                                              v
                                  [Ktor -> YouTubeIngestionService]

+-------------------------+      +----------------------------------+
| PlayerViewModel          |----->| <<interface>> AudioPlayer        |
+-------------------------+      +----------------------------------+
                                              ^
                                              | <|.. realizes
                                  +----------------------------------+
                                  | AudioPlayerImpl (JS)             |
                                  | IndexedDB Blob -> HTMLAudio    |
                                  +----------------------------------+
```

Фактическое воспроизведение на desktop и узком viewport подтвердило маршрут импорта, платформенный аудиоадаптер и текущую стратегию хранения. Новые паттерны и изменения классов отсутствуют.

## BASE-397d007

Дата: 2026-10-21. Ретроспективная фиксация классов уже существующего коммита `397d007`.

```text
+------------------------------------+       +-------------------------+
| YouTubeIngestionService             |------>| YtDlpRunner <<Strategy>>|
|------------------------------------|       +-------------------------+
| - importSlots: Semaphore [ADDED]    |                    ^
| + ingest(url): ImmersionContent    |                    | <|.. realizes
| - ingestContent(url) [EXTRACTED]    |       +-------------------------+
+------------------------------------+       | ProcessYtDlpRunner      |
         | ..> throws when full             +-------------------------+
         v
[IngestionBusyException] --> Ktor route --> HTTP 503

+--------------------------------------------+
| SqlDelightContentRepository <<Repository>>  |
|--------------------------------------------|
| + saveContent(content)                    |
|   persist input index as ordinal [ADDED]   |
+--------------------------------------------+
                  |
                  v
+--------------------------------------------+
| AppDatabaseQueries <<generated SQL API>>    |
|--------------------------------------------|
| + insertSubtitleLine(..., ordinal) [CHANGED]|
| + selectLibrary()                         |
| + selectSubtitlesForContent(id)            |
+--------------------------------------------+
                  |
                  v
[SubtitleLine table: ordinal ADDED; schema 1 -> 2]
```

`YtDlpRunner` задаёт заменяемую стратегию выполнения внешнего процесса; `Semaphore` — механизм ограничения конкурентности, не новый GoF-паттерн. Repository и сгенерированный SQL API сохраняют разделение между domain-моделью и SQLite. Добавление ordinal — изменение persistence-схемы, domain-модель `SubtitleLine` не расширялась. Обновление npm lock-файла не меняет классы приложения.
