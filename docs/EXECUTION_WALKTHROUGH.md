# Langkraft: порядок выполнения в UML-псевдографике и псевдокоде

Срез реализации: `dev`, коммит `3389144`, 23 октября 2026. Это карта существующего кода для ручного чтения. Имена функций сохранены там, где они есть в исходниках; `ON`, `PARALLEL`, `TX`, `WAIT` ниже — поясняющий псевдокод.

Основной маршрут — Web MVP. Backend, Desktop/TUI, восстановление и тестовый режим показаны отдельно. Каталог классов и интерфейсов: [UML_CLASSES](UML_CLASSES.md). Инструкция запуска: [RUNNING](RUNNING.md).

## 1. Как читать и в каком порядке проходить

```text
[объект/модуль]       участник выполнения
<<interface>>        контракт, под которым Koin выдаёт реализацию
  ->                 вызов / запрос
  <--                возвращённое значение / ответ
  ~>                 событие / уведомление / запуск другой корутины
[TX ... COMMIT]       транзакция ОДНОЙ базы данных
[WAIT]               ожидание; остальные задачи могут продолжать работу
{условие}            ветвление
```

Первый проход: разделы 2–9, затем пример занятия в разделе 17. Второй проход: offline, sync и recovery в разделах 10–14. Разделы 15–16 объясняют другие точки входа и завершение.

Программа событийная. После запуска она ждёт кликов, HTTP-запросов, ответов worker и событий аудио. Порядок строк внутри одной корутины сохраняется, но независимые корутины могут чередоваться. `suspend` означает возможность приостановки, а не обязательный запуск нового потока ОС.

Быстрые переходы:

- [2. Общая карта и сводный псевдокод](#2-общая-uml-карта-участников)
- [3. Запуск backend](#3-запуск-настоящего-backend)
- [4. Запуск Web](#4-запуск-web-и-восстановление-локальной-библиотеки)
- [5. Навигация и UI](#5-навигация-и-общий-цикл-ui)
- [6. Импорт](#6-импорт-полный-путь-клиент---backend---клиент)
- [7. Воспроизведение](#7-воспроизведение-реплики-и-повтор-фрагмента)
- [8. Перевод и словарь](#8-слово-перевод-и-сохранение)
- [9. Повторение SRS](#9-srs-показать-ответ-оценить-сохранить-продолжить)
- [10. Offline-аудио](#10-offline-аудио-две-фазы-и-компенсация)
- [11. Вход и sync](#11-авторизация-и-синхронизация)
- [12. SQL, Flow и вкладки](#12-sql-flow-и-обновление-другой-вкладки)
- [13. Восстановление и уборка](#13-recovery-и-уборка)
- [14. Остальные AI-действия](#14-остальные-ai-действия)
- [15. Desktop, TUI и демо](#15-desktop-tui-и-ручное-демо-другие-точки-входа)
- [16. Завершение и ошибки](#16-завершение-и-ошибки-что-прекращается-а-что-остаётся)
- [17. Одно занятие на бумаге](#17-ручная-трассировка-одного-занятия-на-бумаге)
- [18. Точки остановки](#18-где-ставить-точки-остановки-и-что-записывать)

## 2. Общая UML-карта участников

```text
                          ПОЛЬЗОВАТЕЛЬ
                               |
                               v
+----------------------- БРАУЗЕР -----------------------------+
| Main.kt -> MainApp() -> экран Compose                       |
|                         | событие                           |
|                         v                                   |
|                    ViewModel                                |
|                   /    |     \                              |
|            use case  repository  AudioPlayerImpl            |
|                |       |          -> HTMLAudioElement       |
|                |       v                                    |
|                |  DatabaseAccess [Mutex одного клиента]     |
|                |       |                                    |
|                |  BrowserSqlDriver -> DatabaseClient        |
|                |                       | postMessage        |
|                |                       v                    |
|                |  отдельный Web Worker                      |
|                |  SQLite WASM -> IndexedDB VFS (SQL)        |
|                |                                            |
| IndexedDbAudioStorage -> IndexedDB audio/chunks             |
| Web Locks -> владение загрузками и recovery-профилями       |
+---------------|---------------------------------------------+
                | HTTP: JSON или поток аудио
                v
+------------------------ BACKEND ----------------------------+
| EngineMain -> Application.module -> Ktor Routing            |
|       |                  |                                  |
|       |     +------------+-------------+                    |
|       |     |            |             |                    |
|       |  AuthService  SyncService   AI assistant            |
|       |     |            |       Cache -> Gemini / Mock     |
|       |     +------> Exposed -> серверная SQLite            |
|       v                                                     |
| YouTubeIngestionService -> ограниченная очередь -> workers  |
|                              |                              |
|                         YtdlpClient                         |
|                              v                              |
|                    yt-dlp / ffmpeg / ffprobe                |
|                              |                              |
| BackendMediaStorage <-> job-каталоги MP3                    |
|       |                                                     |
| MediaRoutes -> HTTP GET/HEAD/Range под read lease           |
+-------------------------------------------------------------+
```

SQLite клиента и SQLite backend — разные базы. SQLite клиента и IndexedDB с аудио — также разные хранилища. Backend не читает IndexedDB браузера.

### Какие реализации выбирает DI

| Контракт / роль | Web | Desktop/TUI | Backend |
| --- | --- | --- | --- |
| Контент локально | SqlDelightContentRepository | тот же repository | результат хранится в IngestionJobs |
| Словарь локально | SqlDelightVocabularyRepository | тот же repository | BackendVocabularyRepository для sync |
| SQL | BrowserSqlDriver + worker + VFS | JDBC SQLite | Exposed + JDBC SQLite |
| AudioStorage | IndexedDbAudioStorage | JournaledFileAudioStorage | BackendMediaStorage управляет файлами |
| AudioPlayer | HTMLAudioElement | VLCJ / ffplay | не воспроизводит пользователю |
| LinguisticAssistant | RemoteLinguisticAssistant | RemoteLinguisticAssistant | Cache -> Gemini или Mock |

`single` в Koin — один экземпляр, обычно создаваемый при первом запросе. `factory` — новый экземпляр при получении зависимости. Поэтому объявление всех ViewModel в Koin ещё не означает, что они все уже работают.

Исходники: [клиентский DI](../shared/src/commonMain/kotlin/com/langkraft/di/Koin.kt), [Web DI](../shared/src/jsMain/kotlin/com/langkraft/di/WebModule.kt), [backend DI](../backend/src/main/kotlin/com/langkraft/backend/di/BackendModule.kt).

### Сводный псевдокод системы

Ниже два независимых процесса. Браузер не запускает Ktor автоматически. Названия крупных действий — ссылки на разобранные далее сценарии, а не новые функции проекта.

```text
ПРОЦЕСС BACKEND:
    загрузить конфигурацию и проверить JWT secret
    открыть server SQLite; создать сервисы
    восстановить статусы прерванных jobs
    запустить workers и maintenance
    LOOP ожидания HTTP:
        /auth/*     -> регистрация / вход -> JWT
        /ingest     -> принять job -> немедленно вернуть jobId
        /ingest/id  -> вернуть текущий статус и результат
        /media/*    -> выдать файл под read lease
        /ai/*       -> проверить JWT -> cache/provider -> ответ
        /sync       -> проверить JWT -> server SQL TX -> ответ
    // worker независимо получает jobs и выполняет media pipeline

ПРОЦЕСС WEB:
    дождаться WASM; открыть worker / client SQLite
    миграция -> recovery -> DI -> MainApp
    запустить восстановление session.token
    показать Stats
    LOOP событий экрана, сети и аудио:
        открыть Stats    -> подписаться на статистику; попытаться sync
        открыть Account  -> ожидать входа / выхода / Sync now
        открыть Learn    -> подписаться на библиотеку
        IMPORT           -> POST job -> polling -> сохранить Content/Subtitles
        выбрать материал -> прочитать SQL -> загрузить аудиоисточник
        PLAY/PAUSE/SEEK   -> AudioPlayer; события времени -> индекс реплики
        нажать слово     -> запрос перевода -> WordDetailsSheet
        сохранить слово -> TX Vocabulary + PendingSyncChange -> закрыть sheet
        открыть Review  -> snapshot очереди наступивших повторений
        оценить карточку -> SM-2 -> TX сохранения -> следующая карточка
        скачать offline -> stream в AudioStorage -> SQL link -> confirm
        открыть Write   -> запрос коррекции -> показать актуальный результат
        SQL notification -> перечитать Flow -> обновить UI
        сменить экран    -> освободить прежнюю ViewModel
        ошибка операции  -> её локальная ветка ошибки / повтор
        закрыть вкладку  -> остановить контекст браузера
```

Локальная запись слова не вызывает немедленный sync сама по себе. Backend READY не записывает данные в браузер: это делает клиент после получения результата polling.

## 3. Запуск настоящего backend

```text
Оператор       EngineMain       Application.module       Koin / сервисы
   |                |                  |                       |
   | запуск -------->|                 |                       |
   |                | конфигурация --->|                       |
   |                |                  | проверить JWT secret  |
   |                |                  | установить Koin ----->|
   |                |                  | get ingestionService->|
   |                |                  |                       | открыть SQLite
   |                |                  |                       | создать/согласовать таблицы
   |                |                  |                       | recoverInterrupted()
   |                |                  |                       | cleanup медиа
   |                |                  |                       | ~> workers
   |                |                  |                       | ~> периодический cleanup
   |                |                  | JSON, ошибки, JWT     |
   |                |                  | зарегистрировать HTTP |
   |                |                  | [WAIT запросов]       |
```

```text
BACKEND_MAIN(args):
    EngineMain.main(args)                 // Ktor читает конфигурацию

Application.module():
    install PartialContent              // HTTP Range
    REQUIRE jwt.secret.length >= 32      // иначе запуск останавливается
    install Koin(backendModule, config)
    ingestionService = get<YouTubeIngestionService>()
        // Сервис создаётся сразу, даже если импорт ещё никто не запросил.
    ON ApplicationStopping -> ingestionService.close()
    install JSON, StatusPages, JWT verifier
    register root, authRoutes, apiRoutes
    WAIT HTTP requests

YouTubeIngestionService.init:
    recoverInterrupted(): старые незавершённые jobs -> ERROR
    TRY mediaStorage.cleanup()
    PARALLEL:
        worker[1..workerCount]: WAIT очередь; обработать job; повторить
        maintenance: WAIT cleanupInterval; cleanup медиа и terminal jobs; повторить
```

Очередь живёт в памяти процесса, статусы jobs — в серверной SQLite. После перезапуска старые jobs не продолжаются автоматически: они становятся ERROR с предложением повторить импорт.

Исходники: [Application.kt](../backend/src/main/kotlin/com/langkraft/backend/Application.kt), [YouTubeIngestionService.kt](../backend/src/main/kotlin/com/langkraft/backend/YouTubeIngestionService.kt), [IngestionJobRepository.kt](../backend/src/main/kotlin/com/langkraft/backend/IngestionJobRepository.kt).

## 4. Запуск Web и восстановление локальной библиотеки

```text
index.html -> browser-locale.js -> Web main()
                                      |
                                 onWasmReady
                                      v
                           CanvasBasedWindow + Theme
                                      |
                           LaunchedEffect(attempt)
                                      v
                             createWebDatabase()
                              /              \
                         ошибка             handle
                            |                  |
                      текст + Retry       initKoin(...)
                            |                  |
                     attempt++                v
                            +-----------   MainApp()
                                               |
                                после кадров: data-app-ready=true
```

```text
createWebDatabase():
    client = createDatabaseClient()
        -> создать Worker
        -> postMessage(initialize, выбранный profile)
    driver = BrowserSqlDriver(client)
    TRY:
        initializeDatabase(driver)
            -> прочитать версию и фактические столбцы
            -> создать или адаптировать схему в TX
            -> восстановить допустимые source references
            -> установить user_version и foreign_keys
        recoverIncompleteAudio()
            -> удалить только известные incomplete assets без живого владельца
        handle = DatabaseHandle(driver)
        AudioDownloadRecovery.recover()
            -> восстановить отмеченные SQL DOWNLOADING при свободном content lease
        PendingAudioRecovery.recover()
            -> подтвердить связанные / убрать непривязанные pending assets
        RETURN handle
    CATCH:
        driver.close()
        THROW

после успешного createWebDatabase:
    initKoin(commonModule + createWebModule(handle) + AppConfig(current origin))
    показать MainApp
```

Это последовательные проверки перед показом основной UI. При ошибке открытия/восстановления показывается Retry, а не пустая новая библиотека. Worker при открытии использует SQLite WASM и IndexedDB VFS; Kotlin на UI-потоке не исполняет SQL напрямую.

Исходники: [Main.kt](../webApp/src/jsMain/kotlin/Main.kt), [WebDatabaseFactory.kt](../shared/src/jsMain/kotlin/com/langkraft/data/database/WebDatabaseFactory.kt), [DatabaseMigration.kt](../shared/src/commonMain/kotlin/com/langkraft/data/database/DatabaseMigration.kt), [database.worker.js](../shared/src/jsMain/storage/database.worker.js).

## 5. Навигация и общий цикл UI

```text
                              MainApp
                                 |
                         currentScreen=Dashboard
                                 |
           +----------+----------+----------+----------+
           |          |          |          |          |
        Account     Stats      Learn      Review     Write
                               |
                        ContentSelection
                               |
                         выбрать материал
                               v
                         Player(contentId)
```

```text
MainApp():
    session = Koin.get<UserSession>()
    LAUNCH session.restore()            // чтение auth_token из локальной SQLite
    currentScreen = Dashboard
    render выбранный экран

при создании экрана:
    rememberViewModel(key) -> Koin.get<нужная ViewModel>()
    ViewModel.init -> запустить её запросы/подписки
    UI.collectAsState(viewModel.state)

ON действие пользователя:
    UI -> onEvent(...) или метод ViewModel
    ViewModel -> repository / use case / audio adapter
    результат -> updateState(...)
    StateFlow ~> Compose перерисовывает нужное состояние

ON уход с экрана:
    DisposableEffect.onDispose -> viewModel.onCleared()
    -> отменить scope этой ViewModel
```

Восстановление сессии и начальные задачи Dashboard запускаются независимо. Нельзя считать, что JWT уже восстановлен к моменту первого фонового HTTP-запроса. HttpClient подставляет текущее `session.token.value` при каждом новом запросе.

Dashboard подписывается на четыре источника статистики, объединяет их через `combine` и запускает `syncManager.sync()`. Это не бесконечный таймер sync. Словарь обновляет выборку наступивших повторений через свой clock примерно раз в минуту.

Деталь текущей реализации: `totalImmersionSeconds` берётся из суммы длительностей материалов, а не из измерения времени, которое пользователь слушал.

Исходники: [MainApp.kt](../shared/src/commonMain/kotlin/com/langkraft/MainApp.kt), [BaseViewModel.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/BaseViewModel.kt), [DashboardViewModel.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/dashboard/DashboardViewModel.kt).

## 6. Импорт: полный путь клиент -> backend -> клиент

```text
Пользователь  SelectionVM   UseCase/RemoteSource  Ktor      очередь/worker     local SQL
    |              |              |              |               |              |
    | IMPORT ------>|             |              |               |              |
    |              | isImporting  |              |               |              |
    |              | invoke(url)->| POST ingest ->|               |              |
    |              |              |              | validate URL  |              |
    |              |              |              | slot + budget |              |
    |              |              |              | save job IDLE |              |
    |              |              |              | enqueue ------>|             |
    |              |              |<-- jobId ----|               |              |
    |              |              | WAIT 1 сек   |               | yt-dlp ...   |
    |              |              | GET status ->|               |              |
    |              |              |<-- status ---|<-- SQL status --|             |
    |              |              | ... polling до READY/ERROR ...             |
    |              |              |<-- content при READY --------|              |
    |              |              | saveContent ------------------------------>|
    |              |              |<--------------------------- COMMIT --------|
    |              |<-- Result ---|                                            |
    |              | isImporting=false                                        |
    |<-- библиотека обновилась через SQLDelight Flow ---------------------------|
```

```text
ContentSelectionViewModel.importContent(url):
    state.isImporting = true; state.error = null
    TRY IngestContentUseCase(url)
        RemoteSource.fetchFromYouTube(url):
            POST /api/ingest { url }
            REQUIRE успешный HTTP status
            jobId = ответ.jobId
            LOOP:
                WAIT 1000 ms
                job = GET /api/ingest/jobId
                IF HTTP 404 -> ошибка "Job not found"
                IF другой неуспешный HTTP -> ошибка
                IF job.status=ERROR -> ошибка импорта
                IF job.status=READY AND content != null -> RETURN content
                ELSE продолжить ожидание
        LocalContentRepository.saveContent(content)
        RETURN Result.success(content)
    ошибка операции -> Result.failure -> state.error
    отмена корутины -> пробросить CancellationException
    FINALLY state.isImporting = false
```

В RemoteSource нет общего deadline для всей polling-петли. HTTP timeout относится к отдельному запросу. Остановка клиентского ожидания не отменяет уже принятое backend задание.

### Что делает worker

```text
IDLE -> FETCHING_METADATA -> DOWNLOADING_AUDIO -> PARSING_SUBTITLES
     -> GENERATING_WAVEFORM -> READY
       любой отказ / превышение бюджета / прерывание -> ERROR

startIngestion(url):
    validate URL
    TRY захватить slot; иначе 503 + Retry-After
    jobId = UUID
    beginImport(jobId) -> lease и резерв места
        // нехватка места: отказ до записи job, 503 + Retry-After
    save job(IDLE) в SQLite
    отправить (jobId, url, lease) в ограниченную очередь
    RETURN jobId

WORKER:
    TAKE job
    TRY:
        getVideoInfo -> yt-dlp metadata JSON
        проверить videoId, live, длительность, известный filesize/estimate
        downloadContent -> yt-dlp + ffmpeg -> MP3 + немецкие субтитры
        SrtParser.parse -> упорядоченные SubtitleLine
        WaveformExtractor.extract -> waveform
        проверить бюджет; удалить intermediates
        build ImmersionContent
        mediaStorage.publish(jobId) {
            записать READY + content в серверную SQLite
        }
    CATCH:
        остановить внешние процессы при отмене/ошибке бюджета
        удалить job-файлы насколько возможно; записать ERROR
    FINALLY:
        закрыть import lease; освободить slot
    TAKE следующую job
```

Контроль actual bytes работает параллельно обработке при включённых лимитах. Он периодический; это не жёсткая файловая квота.

### Что сохраняется у клиента

В `saveContent` одна SQL-транзакция обновляет Content, реплики с ordinal и восстановленные source references словаря. Повторный импорт сохраняет существующую COMPLETED offline-копию, если новая структура не заменяет её завершённой копией. Прежние ненужные реплики удаляются; связанные словарные записи не должны исчезать из-за замены Content.

`job.status=READY` означает готовый результат backend. `Content.downloadStatus=COMPLETED` означает локальную offline-копию клиента. Это разные статусы.

Важная деталь DTO: backend заполняет `localAudioPath` своим абсолютным путём. При начальном IDLE выбор `getPlaybackUrl()` использует `audioUrl`. Путь пригодной offline-копии браузера появляется после `AudioStorage.store()` и SQL-привязки; серверный путь не является браузерным `indexeddb-audio:` объектом.

При ручной трассировке перезапуска отдельно проверьте это поле: текущий `initializeDatabase` для уже существующей схемы переводит строки с `localAudioPath != null` и `downloadStatus=IDLE` в COMPLETED. Эта SQL-ветка не проверяет существование или принадлежность файла клиенту. Схема здесь фиксирует фактическое выполнение; автоматической проверки пригодности серверного пути в браузере не следует из наличия поля.

Исходники: [ContentSelectionViewModel.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/content/ContentSelectionViewModel.kt), [IngestContentUseCase.kt](../shared/src/commonMain/kotlin/com/langkraft/domain/usecase/IngestContentUseCase.kt), [BackendRemoteSource.kt](../shared/src/commonMain/kotlin/com/langkraft/data/repository/BackendRemoteSource.kt), [YouTubeIngestionService.kt](../backend/src/main/kotlin/com/langkraft/backend/YouTubeIngestionService.kt), [YtdlpClient.kt](../backend/src/main/kotlin/com/langkraft/backend/YtdlpClient.kt), [SqlDelightContentRepository.kt](../shared/src/commonMain/kotlin/com/langkraft/data/repository/SqlDelightContentRepository.kt).

## 7. Воспроизведение, реплики и повтор фрагмента

```text
выбрать материал -> Screen.Player(id)
  -> LaunchedEffect(id) -> PlayerEvent.LoadContent(id)
  -> repository.getContentById(id): Content + отдельная выборка SubtitleLine
  -> ImmersionContent.getPlaybackUrl(baseUrl)
       {COMPLETED и localAudioPath != null} -> локальный адрес
       {иначе}                             -> audioUrl через backend origin
  -> AudioPlayerImpl.load(url, requestId)
       {indexeddb-audio:...} -> readAudioBlob -> Blob URL
       {HTTP}               -> HTMLAudioElement.src

PLAY -> audio.play() -> onplay ~> isPlaying=true -> timer каждые 100 ms
                                                        |
                                      currentTimeMs <----+
                                             |
                                  PlayerVM.handleTimeUpdate
                                             |
                           выбрать реплику по startMs <= t < endMs
                                             |
                        state.currentSubtitleIndex -> UI
```

```text
loadContent(id):
    увеличить loadGeneration и audioRequest
    отменить прежнюю loadJob; сбросить языковые запросы; pause()
    загрузить Content + subtitles из SQL
    IF запрос уже устарел -> не применять ответ
    IF Content отсутствует -> state.error
    ELSE state.content = content; player.load(выбранный URL, audioRequest)

AudioPlayerImpl.load(...):
    увеличить generation; отменить старую загрузку
    снять старые handlers; pause; убрать src; освободить старый Blob URL
    создать новый HTMLAudioElement и привязать handlers к generation
    IF локальный объект -> асинхронно прочитать Blob
    ELSE установить HTTP src
    IF PLAY уже был нажат до получения Blob -> начать после готовности

ON время t:
    найти текущую реплику
    IF loop включён AND достигнут конец реплики / узкое окно после конца:
        seekTo(startMs)
    ELSE обновить время, индекс и состояние прокрутки

ON SeekTo / StepBack -> player.seekTo(...)
ON SetPlaybackSpeed -> player.playbackRate
```

Если сохранённого локального объекта нет или он неполон, появляется возможность PlayOnline. Сетевое переключение выполняется по явному действию пользователя. Ошибка доступа к storage или autoplay не равна отсутствию локального объекта. Запоздавшие media events от старого источника отсекаются generation/requestId.

Backend выдаёт файл через read lease до завершения HTTP-ответа; GET, HEAD и Range проходят через MediaRoutes и PartialContent. Уборка не должна удалить файл активного читателя.

HTTP-аудио для HTMLAudioElement запрашивает сам браузер. JSON-операции и поток offline-скачивания идут через клиентский Ktor HttpClient; это разные механизмы запросов.

Исходники: [PlayerViewModel.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/player/PlayerViewModel.kt), [Web AudioPlayerImpl.kt](../shared/src/jsMain/kotlin/com/langkraft/audio/AudioPlayerImpl.kt), [MediaRoutes.kt](../backend/src/main/kotlin/com/langkraft/backend/MediaRoutes.kt).

## 8. Слово: перевод и сохранение

```text
UI/слово -> PlayerVM -> PlayerLinguisticDelegate -> RemoteLinguisticAssistant
                             |                               |
                    selectionGeneration++                    | POST translate-word
                    отменить прежний wordJob                 v
                             |                          JWT middleware
                             |                               |
                             |                         AI cache -> provider
                             |<----------- TranslationResult-+
                    {generation совпадает}
                             |
                    state.wordTranslation -> WordDetailsSheet
                             |
                       ADD TO DICTIONARY
                             v
                    PlayerVM.saveWord(word)
                             |
                   VocabularyRepository.saveWord
                             v
                   [TX Vocabulary + PendingSyncChange COMMIT]
                             |
                    закрыть sheet при прежнем выборе
```

```text
WordClicked(word, line):
    запомнить слово и контекст; показать ожидание
    result = await assistant.translateWord(word, line.originalText)
    IF запрос отменён / выбран другой word / другой материал -> не применять
    ELSE показать translation, lemma, partOfSpeech
    HTTP/AI error -> wordError + возможность повторить

WordDetailsSheet:
    wordId = remember(word, line.id) { UUID }
    ON ADD TO DICTIONARY:
        создать VocabularyWord с word, lemma, translation,
            contextSentence, contentId, subtitleLineId
        PlayerVM.saveWord(word)

PlayerVM.saveWord:
    IF запись уже выполняется -> RETURN
    isSavingWord=true
    TRY await repository.saveWord(word)
        закрыть sheet только если выбранное слово не сменилось
    ERROR -> оставить sheet, показать wordError
    FINALLY isSavingWord=false

repository.saveWord(word):
    DatabaseAccess.run {
        TX:
            revision = max(now, pending.timestamp+1, stored.lastUpdated+1)
            upsert Vocabulary(..., lastUpdated=revision)
            upsert PendingSyncChange(wordId, UPSERT, revision)
        COMMIT
    }
```

Сначала локальный COMMIT. Успешный перевод сам по себе слово не сохраняет, а успешное локальное сохранение не означает выполненный sync.

Начальные поля новой модели: NEW, intervalDays=0, lapseCount=0, nextReviewMs=0. В текущем WordDetailsSheet `addedAt` явно не передаётся и остаётся значением модели по умолчанию 0; repository назначает собственную ревизию `lastUpdated`.

На настоящем backend все четыре AI-операции защищены JWT. Backend DI оборачивает Gemini/Mock в CachingLinguisticAssistant. Значение `mock_key` выбирает Mock; это fallback конфигурации при отсутствии ключа. Ключ Gemini остаётся на сервере.

Исходники: [PlayerLinguisticDelegate.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/player/PlayerLinguisticDelegate.kt), [WordDetailsSheet.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/player/WordDetailsSheet.kt), [RemoteLinguisticAssistant.kt](../shared/src/commonMain/kotlin/com/langkraft/data/ai/RemoteLinguisticAssistant.kt), [SqlDelightVocabularyRepository.kt](../shared/src/commonMain/kotlin/com/langkraft/data/repository/SqlDelightVocabularyRepository.kt).

## 9. SRS: показать ответ, оценить, сохранить, продолжить

```text
создать SrsTrainingViewModel
          |
          v
LoadQueue -> getWordsToReview().first() -> snapshot очереди
          |                                  |
       ошибка                           первая карточка
          |                                  |
     TRY AGAIN                         SHOW ANSWER
                                             |
                                    AGAIN/HARD/GOOD/EASY
                                             |
                                  isSaving=true; кнопки disabled
                                             |
                                   Sm2Algorithm.calculateNextReview
                                             |
                                   repository.saveWord [TX]
                                      /              \
                                  ошибка           COMMIT
                                    |                |
                          та же карточка + error     убрать первую карточку
                                    |                |
                              повтор оценки          + AGAIN: добавить обновлённую в хвост
                                                     |
                                      следующая карточка / пустая очередь
                                                     |
                                      isFinished -> onFinish -> Dashboard
```

```text
ON LoadQueue:
    IF isLoading OR isSaving -> игнорировать повторное событие
    isLoading=true; error=null
    TRY queue = await repository.getWordsToReview().first()
        currentWord=queue.firstOrNull; remainingCount=queue.size
        isFinished=queue.isEmpty
    ERROR -> сообщение загрузки; это не успешное пустое занятие
    FINALLY isLoading=false

ON SubmitResult(quality):
    IF busy OR currentWord отсутствует -> RETURN
    isSaving=true; error=null
    TRY:
        updated = algorithm.calculateNextReview(currentWord, quality)
        await repository.saveWord(updated)
        проверить, что корутина не отменена
        queue = queue.drop(1)
        IF quality=AGAIN -> queue += updated
        currentWord=queue.firstOrNull; answerVisible=false
        remainingCount=queue.size; isFinished=queue.isEmpty
    ERROR -> оставить прежнюю карточку, ответ и очередь; показать error
    CANCEL -> пробросить отмену
    FINALLY isSaving=false
```

Карточка покидает очередь только после успешной записи. Очередь берётся первой эмиссией Flow, поэтому не пересобирается посреди занятия от каждого SQL-уведомления.

### Псевдокод текущего SM-2

```text
quality: AGAIN=0, HARD=3, GOOD=4, EASY=5
now = текущее время в ms
IF quality < 3:
    intervalDays=0; nextReviewMs=now
    lapseCount += 1; status=LEARNING; lastUpdated=now
ELSE:
    d = 5-quality
    easeFactor = max(1.3, oldEase + 0.1 - d*(0.08+d*0.02))
    intervalDays = CASE oldInterval: 0 -> 1; 1 -> 6; иначе round(oldInterval*easeFactor)
    nextReviewMs = now + intervalDays*86400000
    status = MASTERED если intervalDays>30, иначе LEARNING
    lastUpdated=now
```

Порог MASTERED именно `>30`. Повторный HTTP-запрос для SRS не нужен: это локальное вычисление и локальная запись со строкой PendingSyncChange.

Исходники: [SrsTrainingViewModel.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/srs/SrsTrainingViewModel.kt), [SrsTrainingView.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/srs/SrsTrainingView.kt), [SrsEngine.kt](../shared/src/commonMain/kotlin/com/langkraft/domain/srs/SrsEngine.kt).

## 10. Offline-аудио: две фазы и компенсация

```text
OfflineDownloadDelegate -> AudioDownloaderImpl -> content lease
                                      |
                        [SQL TX: DOWNLOADING + recovery marker]
                                      |
                          HTTP GET: status/MIME/length
                                      |
                          IndexedDbAudioStorage.store
                                      |
                 beginAudio -> append chunks -> commitAudio
                                      |
                   готовый pending объект indexeddb-audio:id
                                      |
                   [SQL TX: COMPLETED + localAudioPath,
                            удалить recovery marker]
                                      |
                          storage.confirmLinked(location)
                                      |
                          release content lease
```

```text
downloadAudio(content):
    acquire content lease; иначе "already running"
    запомнить прежнюю SQL-строку
    короткая NonCancellable SQL TX: DOWNLOADING + protocol marker
    скачать поток (не читать весь файл в память)
        REQUIRE HTTP 200 и допустимый Content-Type
        storage.store(expectedBytes):
            beginAudio -> manifest complete=false + asset lease
            LOOP читать до 256 KiB; appendAudio(chunk)
            commitAudio -> проверить размер/непустоту; complete=true; release asset lease
            RETURN indexeddb-audio:id
    короткая NonCancellable SQL TX:
        перечитать Content; проверить, что строка не удалена/не заменена
        записать COMPLETED + новый location; убрать protocol marker
    linked=true
    confirmLinked(location) -> pending=false
    RETURN location
    FINALLY release content lease

если ошибка ДО SQL link:
    перед удалением новой публикации проверить ВСЕ SQL-ссылки на неё
    если нет ссылок -> попытаться удалить объект
    прежнюю строку восстановить только если она всё ещё принадлежит этой загрузке
    если original.localAudioPath != null -> восстановить прежний статус
    иначе отмена -> IDLE, ошибка -> ERROR

если ошибка ПОСЛЕ SQL link:
    не удалять объект, на который уже ссылается Content
    pending-маркер может остаться до следующего recovery
```

Нет общей транзакции SQLite + audio IndexedDB. Именно поэтому нужны pending-маркер, проверка ссылок и компенсация. Content lease удерживается дольше asset lease: до завершения SQL-привязки/подтверждения.

Исходники: [AudioDownloaderImpl.kt](../shared/src/commonMain/kotlin/com/langkraft/data/repository/AudioDownloaderImpl.kt), [IndexedDbAudioStorage.kt](../shared/src/jsMain/kotlin/com/langkraft/data/audio/IndexedDbAudioStorage.kt), [storage/index.js](../shared/src/jsMain/storage/index.js), [OfflineDownloadDelegate.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/player/OfflineDownloadDelegate.kt).

## 11. Авторизация и синхронизация

### Вход и выход

```text
Account -> UserSession.signIn(email, password, optional name)
        -> POST /api/auth/login или /register
        -> AuthService -> BCrypt verify / hash + создать пользователя
        -> JwtService -> AuthResponse(token, user)
        -> локальная SQLite SyncMetadata[auth_token]=token
        -> session.token.value=token
        -> Account запускает sync(force=true)

каждый последующий HttpClient request:
    если session.token.value != null -> Authorization: Bearer ...

Sign out:
    записать пустой auth_token; session.token=null
    библиотека и словарь остаются в локальной БД
```

JWT сохраняется в SyncMetadata клиентской SQL-базы; сервер хранит пользователей и хеши паролей. Регистрация/вход публичные, AI и sync защищены JWT. Импорт, чтение статуса и выдача медиа в текущем Routing публичные.

### Sync как последовательность с подтверждением ревизий

```text
Клиент SQL             SyncManager / Repository       Backend SQL
    |                           |                         |
 Vocabulary + PendingSyncChange |                         |
    |---- snapshot pending ---->|                         |
    |                           | POST /api/sync -------->|
    |                           |                         | JWT email -> user
    |                           |                         | TX: конфликт/UPSERT/DELETE
    |                           |                         | новый serverUpdated cursor
    |                           |<-- changes + cursor ----|
    |<---- применить в TX ------|                         |
    | проверить новую правку    |                         |
    | удалить только ack старой |                         |
    | COMMIT ------------------>|                         |
    |<-- сохранить last cursor -|                         |
```

```text
SyncManager.sync(force):
    если не force и с последней попытки <60 сек -> RETURN
    если sync уже идёт -> RETURN
    под mutex:
        прочитать last_sync_timestamp
        snapshot PendingSyncChange и соответствующих слов
        POST SyncRequest(cursor, clientChanges)
        backend в одной TX:
            проверить владельца wordId
            принять более новый word.lastUpdated
            при равной ревизии DELETE имеет приоритет
            отметить deleted вместо физического удаления sync-записи
            назначить монотонный serverUpdated
            вернуть чужие/неприменённые изменения после старого cursor
        клиент в одной TX:
            не затирать локальную правку, появившуюся после отправки snapshot
            применить serverChanges
            если исходного видео нет, сохранить source IDs в metadata,
                а локальные foreign keys оставить допустимыми
            удалить ack только по совпавшим wordId/timestamp/changeType
        после успешной обработки обновить last_sync_timestamp
    ERROR -> syncError; локальный словарь остаётся
```

`word.lastUpdated` — ревизия изменения слова; `serverUpdated` — курсор выдачи серверных изменений. Это разные значения. Видео, аудио и вся клиентская библиотека этим sync-протоколом не копируются.

Sync запускается из Dashboard и кнопкой Sync now / после входа. Ограничитель 60 секунд не является самостоятельным таймером запуска.

Исходники: [UserSession.kt](../shared/src/commonMain/kotlin/com/langkraft/data/sync/UserSession.kt), [AccountView.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/AccountView.kt), [SyncManager.kt](../shared/src/commonMain/kotlin/com/langkraft/data/sync/SyncManager.kt), [backend sync](../backend/src/main/kotlin/com/langkraft/backend/db/BackendVocabularyRepository.kt), [AuthService.kt](../backend/src/main/kotlin/com/langkraft/backend/AuthService.kt).

## 12. SQL, Flow и обновление другой вкладки

```text
repository -> DatabaseAccess.run [локальный Mutex]
           -> SQLDelight -> BrowserSqlDriver
           -> DatabaseClient.request(id, action, sql, params)
           -> Worker.postMessage
           -> очередь Promise -> SQLite statement -> IndexedDB VFS
           <-- ответ с тем же id / error
           <-- завершение await

успешный COMMIT + SQLDelight notifyListeners
           ~> локальные Query.Listener
           ~> repository Flow перечитывает SQL через DatabaseAccess
           ~> ViewModel.state -> UI
           ~> BroadcastChannel: только hint "committed"
                     |
                     v
               другая вкладка / тот же profile
               -> invalidate Query.Listener -> собственное чтение SQL
```

Worker выполняет сообщения последовательно. DatabaseAccess не даёт локальным repository перемешивать операции одной SQL-сессии. Это не глобальный mutex всех вкладок.

Во время локальной SQL-транзакции входящий hint откладывается до её завершения. Rollback не публикует успешный commit-hint. Полученное уведомление не рассылается обратно. Focus/pageshow/возврат видимости дают запасное перечитывание, если BroadcastChannel отсутствует или уведомление потерялось.

Через канал не передаются строки SQL, JWT, аудио или словарь. Канал сообщает только «перечитай свои данные».

Исходники: [DatabaseAccess.kt](../shared/src/commonMain/kotlin/com/langkraft/data/database/DatabaseAccess.kt), [BrowserSqlDriver.kt](../shared/src/jsMain/kotlin/com/langkraft/data/database/BrowserSqlDriver.kt), [database.worker.js](../shared/src/jsMain/storage/database.worker.js), [database-events.js](../shared/src/jsMain/storage/database-events.js).

## 13. Recovery и уборка

### Автоматическое Web recovery при старте

```text
incomplete manifest известного протокола
    -> свободен asset lock? нет: оставить
    -> повторно проверить manifest в deleting TX
    -> удалить manifest + chunks атомарно в audio IndexedDB

SQL DOWNLOADING
    -> свободен content lease и marker совпадает с протоколом?
    -> нет: оставить
    -> да: старый path есть ? COMPLETED : ERROR; удалить marker

complete + pending asset
    -> свободен content lease и известен протокол?
    -> проверить все Content, ссылающиеся на location
    -> есть ссылка: pending=false
    -> нет ссылок: удалить manifest/chunks
```

Наличие старого path в SQL само по себе не проверяет воспроизводимость файла. При проигрывании может понадобиться явный PlayOnline. Неизвестные legacy/no-lock записи recovery не удаляет только из-за возраста.

### Ручной recovery.html

```text
пользователь выбирает копирование / экспорт / импорт
    -> readonly snapshot источника, не запуск writable VFS оригинала
    -> cursor проверяет byte/record лимит во время чтения
    -> создать НОВЫЙ recovered-v2 профиль
    -> в копии проверить SQL, очистить token/старые download markers
    -> связать только полное и корректное аудио; исправить статусы копии
    -> открыть/выдать ссылку только при успехе

удаление managed recovery-профиля:
    exclusive profile lease, иначе отказ
    registry: active -> deleting
    очистить известные stores SQL/audio
    registry: deleted
    // частичный отказ: deleting остаётся, повтор завершает очистку
```

Экспорт дополнительно делает VACUUM копии, чтобы удалённые токены не остались в исторических страницах. JSON/base64 архив обрабатывается в памяти; текущие лимиты описаны в [LEGACY_RECOVERY](LEGACY_RECOVERY.md). Полноценного потокового архива нет.

### Backend maintenance

```text
startup и затем через cleanupInterval:
    если media TTL включён:
        удалить истёкшие распознанные job-каталоги
        только без import leases и readers
    удалить старые строки jobs только READY/ERROR

при чтении файла:
    acquireMedia -> увеличить reader count
    отправить HTTP-ответ
    FINALLY закрыть read lease
```

Media TTL и byte limits по умолчанию opt-in. Уборка серверного MP3 не удаляет уже скачанную браузерную копию. Удаление старой строки job и удаление файла — отдельные операции. BackendMediaStorage рассчитан на одного владельца downloads-каталога.

Исходники: [AudioDownloadRecovery.kt](../shared/src/commonMain/kotlin/com/langkraft/data/audio/AudioDownloadRecovery.kt), [PendingAudioRecovery.kt](../shared/src/commonMain/kotlin/com/langkraft/data/audio/PendingAudioRecovery.kt), [recovery.js](../shared/src/jsMain/storage/recovery.js), [profile-lifecycle.js](../shared/src/jsMain/storage/profile-lifecycle.js), [BackendMediaStorage.kt](../backend/src/main/kotlin/com/langkraft/backend/BackendMediaStorage.kt).

## 14. Остальные AI-действия

| Действие | Последовательность |
| --- | --- |
| Перевод реплики | ToggleTranslation -> translateSentence -> `/api/ai/translate-sentence` -> state.sentenceTranslations |
| Разбор реплики | DeepAnalysisClicked -> analyzeSentence -> `/api/ai/analyze-sentence` -> state.deepAnalysis |
| Леммы реплики | ToggleLemmatization -> analyzeSentence -> map original/lemma -> state.lemmatizedSentences |
| Письменное упражнение | WritingViewModel.submitForCorrection -> correctText -> `/api/ai/correct-text` -> state.correction |
| Memorization | локальное состояние скрываемого текста; само действие не вызывает AI |

Переводы/разборы в состоянии плеера не равны сохранённым VocabularyWord. Для слова нужна отдельная кнопка ADD TO DICTIONARY.

У WritingViewModel новый текст отменяет старую correctionJob и увеличивает generation. Запоздавшая коррекция не подставляется к уже изменённому тексту. У делегата плеера также есть generation материала и версии запросов реплик, чтобы старый ответ не испортил новый экран.

Текст и коррекция WritingViewModel находятся в состоянии ViewModel. В этом пути нет записи текста в SQLite; при чтении схемы не следует подразумевать автоматически сохраняемый дневник.

Исходники: [PlayerLinguisticDelegate.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/player/PlayerLinguisticDelegate.kt), [WritingViewModel.kt](../shared/src/commonMain/kotlin/com/langkraft/ui/writing/WritingViewModel.kt), [Routing.kt](../backend/src/main/kotlin/com/langkraft/backend/Routing.kt).

## 15. Desktop, TUI и ручное демо: другие точки входа

Android target сейчас отключён. Ниже описаны действующие Desktop/TUI точки входа и инструмент ручной Web-проверки.

```text
Desktop main(args):
    initKoin(common + createDesktopModule(isTui=false))
    по запросу зависимостей открыть JVM SQLite, выполнить migrations/recovery
    AudioStorage = JournaledFileAudioStorage (файлы + pending journal)
    AudioPlayer = VLCJ; при недоступности -> ffplay fallback
    --smoke: получить зависимости, release/close/stopKoin, RETURN
    иначе Compose Window -> тот же MainApp

TUI main(args):
    initKoin(common + createDesktopModule(isTui=true))
    UserSession.restore() через runBlocking
    --smoke: виртуальный терминал, wiring check, закрыть зависимости, RETURN
    иначе TuiApp.start() -> Lanterna menu -> окна и общие ViewModel
    AudioPlayer = ffplay
```

JVM-профиль: `LANGKRAFT_DATA_DIR` или `~/.langkraft`; файл SQLite и каталог audio находятся в нём. Desktop/TUI могут использовать один профиль. Native content lease и файловый pending-журнал заменяют Web Locks и IndexedDB audio. TUI не является Compose UI, и набор кнопок/жизненный цикл окон у него свой.

### `npm run demo`

```text
Node tests/web-app.test.js --demo
    -> создать localhost fixture-сервер на свободном порту
    -> сгенерировать WAV-сигнал
    -> headed Chromium, новый временный context
    -> открыть тот же Web bundle; дождаться appReady
    -> WAIT действий участника
         /api/ingest -> готовый тестовый материал
         /api/media -> звуковой сигнал
         /api/ai/translate-word -> заданные Hallo/Welt переводы, без входа
    -> вкладка закрыта -> закрыть browser, HTTP connections и server
```

Демо не запускает Ktor, yt-dlp или Gemini. Это реальный клиентский UI и реальная локальная SQLite/IndexedDB, но подменённый сервер. Он не доказывает работу настоящего импорта, авторизации или качество перевода. Обычный `npm run test:app` использует headless-ветку этого скрипта, fixture JWT и автоматические проверки.

Исходники: [Desktop main](../shared/src/desktopMain/kotlin/com/langkraft/main.kt), [CliMain.kt](../shared/src/desktopMain/kotlin/com/langkraft/CliMain.kt), [DesktopModule.kt](../shared/src/desktopMain/kotlin/com/langkraft/di/DesktopModule.kt), [demo/test script](../tools/web-storage-tests/tests/web-app.test.js).

## 16. Завершение и ошибки: что прекращается, а что остаётся

```text
Уход с Compose-экрана
    -> ViewModel.onCleared -> scope.cancel
    -> PlayerViewModel дополнительно release audio
    -> AudioPlayer снимает handlers, останавливает timer/load, revoke Blob URL
    -> уже принятый backend import продолжает работу независимо от экрана

Явный close DatabaseClient/driver
    -> убрать DOM listeners / BroadcastChannel
    -> terminate worker; отклонить ожидающие запросы
    -> shutdown контекста снимает Web Locks

Закрытие обычной Web-вкладки
    -> браузер уничтожает JS/worker и освобождает locks
    -> долговечные SQL/IDB данные остаются в профиле
    -> оставшиеся протокольные следы проверяются при следующем запуске
    // Не следует считать, что при аварии обязательно выполнился finally.

ApplicationStopping backend
    -> ingestionService.close: запретить admission, закрыть канал, cancel lifetime
    -> queued jobs: ERROR, cleanup, release lease/slot
    -> активные корутины: отмена -> остановить процессы -> cleanup -> ERROR
```

Явные Web close-функции существуют, но Web main не гарантирует исполнение общего graceful shutdown при каждом закрытии вкладки. Поэтому восстановление основано на долговечных метках, а не только на finally.

| Где отказ | Что видно / что проверять |
| --- | --- |
| Открытие локальной БД | стартовый error + Retry, MainApp ещё не показан |
| Admission импорта | HTTP 400 для неподходящего URL; 503 при перегрузке/нехватке бюджета |
| Worker импорта | job ERROR; клиент завершает polling ошибкой |
| Локальное сохранение контента после READY | import error у клиента; серверный результат уже может существовать |
| Чтение/проигрывание аудио | player.error, requestId; локальное отсутствие отдельно от storage/autoplay |
| Перевод слова | wordError, повтор; слово автоматически не сохраняется |
| Сохранение слова / SRS | оставить текущий UI для повтора; не показывать ложный успех |
| Sync | syncError; не удалять неподтверждённые ревизии |
| Неизвестные legacy recovery-данные | консервативное сохранение исходника |

## 17. Ручная трассировка одного занятия «на бумаге»

Предусловия успешного прохода: пустая клиентская библиотека и словарь, доступный backend, видео с подходящими немецкими субтитрами и выполненный вход до запроса перевода. Без JWT у настоящего backend шаг T5 пойдёт в ветку HTTP 401. В fixture-демо вход не требуется, а результат импорта задан сервером заранее.

Символические идентификаторы: `J` — job, `V` — content/video, `L` — реплика, `W` — слово, `A` — локальный audio asset. В реальном коде это реальные ID/UUID; буквы ниже нужны только для записи последовательности.

В формулах `T9` и `T10` обозначают время соответствующего действия в миллисекундах.

```text
T0: приложение открыто, локальная библиотека пуста
T1: IMPORT(url) -> backend создаёт J(IDLE)
T2: worker -> READY(content V, subtitles [L], audioUrl)
T3: клиент saveContent(V) COMMIT -> библиотека показывает V
T4: открыть V -> player.load(HTTP audioUrl); PLAY -> time events
T5: нажать Hallo в L -> HTTP translation -> показать результат
T6: ADD TO DICTIONARY -> save W + pending UPSERT в одной TX
T7: открыть Review -> snapshot [W]
T8: SHOW ANSWER -> меняется только UI
T9: AGAIN -> рассчитать W1 -> save W1 COMMIT -> очередь [W1]
T10: SHOW ANSWER -> GOOD -> рассчитать W2 -> save W2 COMMIT -> очередь []
T11: isFinished -> Dashboard
T12: перезагрузка -> открыть ту же SQL БД -> W2 сохранён,
     его nextReviewMs ещё в будущем -> немедленно повторять нечего
```

| После шага | Backend job | Client Content | Client Vocabulary | PendingSyncChange / UI |
| --- | --- | --- | --- | --- |
| T1 | J: IDLE | нет V | пусто | isImporting=true |
| T2 | J: READY | ещё может не быть V | пусто | HTTP результат получен |
| T3 | READY | V и L сохранены; offline ещё не загружен | пусто | library Flow обновился |
| T5 | READY | без изменений | пусто | перевод только в PlayerState |
| T6 | READY | без изменений | W: NEW, interval=0, lapse=0, nextReview=0 | UPSERT с локальной ревизией |
| T8 | READY | без изменений | без изменений | answerVisible=true |
| T9 | READY | без изменений | W1: LEARNING, interval=0, lapse=1, nextReview=T9 | UPSERT новой ревизии; карточка снова в очереди |
| T10 | READY | без изменений | W2: interval=1, lapse=1, nextReview=T10+86400000 | UPSERT новой ревизии; очередь пуста |
| T12 | READY или позже очищен по job expiry | V сохранён локально | W2 перечитан из SQLite | pending может остаться до успешного sync |

Фоновый sync может вклиниться между этими шагами, поэтому наличие pending в таблице проверяют с учётом успешного/неуспешного sync. В fixture-демо серверный job — подмена, а не настоящая строка Ktor SQLite.

Для отдельного прохода offline вставьте между T4 и T5 шаги раздела 10. Тогда отдельно записывайте `Content.downloadStatus`, `localAudioPath`, SQL recovery marker, IDB complete/pending и владельца content lease.

## 18. Где ставить точки остановки и что записывать

| Сценарий | Функция / место | Значения для ручной проверки |
| --- | --- | --- |
| Старт Web | createWebDatabase, initializeDatabase | profile, schema version, recovery markers, ошибка |
| DI/экран | MainApp, rememberViewModel, init ViewModel | currentScreen, созданный экземпляр, активные подписки |
| Запуск импорта | startIngestion | url, jobId, slot, import lease, факт записи job |
| Worker | processIngestion | status перед этапом, videoId, файлы, бюджет |
| Клиентский импорт | fetchFromYouTube, saveContent | HTTP status, job.status, момент локального COMMIT |
| Player | loadContent, AudioPlayerImpl.load | loadGeneration, requestId, выбранный URL, источник ошибки |
| Перевод | handleWordClicked | selectedWord, line.id, selectionGeneration, актуальность ответа |
| Слово | saveWord в VM/repository | wordId, saveInFlight, revision, момент COMMIT |
| SRS | handleSubmitResult | queue, quality, isSaving, nextReviewMs, момент смены карточки |
| Offline | downloadWithLease | previous, location, linked, marker, обе lease |
| Sync | fetchServerChanges / synchronize | snapshot pending, revision, cursor, ack predicate |
| Recovery | recover/resolvePending | известный protocol, свободен ли lease, все SQL-ссылки |

Практический порядок ручного просмотра: откройте файл из ссылки нужного раздела, найдите названную функцию, затем переходите по стрелкам схемы. На каждом `[WAIT]` отмечайте, какие соседние задачи могут изменить читаемые значения. На каждом `[TX]` отдельно отмечайте COMMIT/ROLLBACK, а после него — кто получил уведомление.

Для фактической проверки интерфейса используйте [FIRST_LESSON](FIRST_LESSON.md). Автоматическое подтверждение основного пути — [web-app.test.js](../tools/web-storage-tests/tests/web-app.test.js). Этот обзор объясняет выполнение, а не заменяет проверку приложения или полный справочник всех строк исходников.
