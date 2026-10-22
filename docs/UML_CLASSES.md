# UML классов Langkraft

Актуальная карта основных классов; вспомогательные UI-компоненты и все поля DTO опущены. Пунктир — использование, сплошная стрелка — удерживаемая зависимость, треугольник — наследование/реализация. `MainApp`, Koin DSL и Routing — функции, поэтому они описаны в [руководстве](DEVELOPER_GUIDE.md), а не выданы за классы. Mermaid отображается на GitHub; исходники диаграмм находятся в этом Markdown.

## Модели и SRS

```mermaid
classDiagram
    class ImmersionContent {
        +String id
        +String audioUrl
        +String localAudioPath
        +DownloadStatus downloadStatus
        +List subtitles
        +List waveform
        +getPlaybackUrl(baseUrl) String
    }
    class SubtitleLine {
        +String id
        +String contentId
        +Long startMs
        +Long endMs
        +String originalText
        +String translationText
    }
    class VocabularyWord {
        +String id
        +String word
        +String contentId
        +String subtitleLineId
        +Long nextReviewMs
        +Int intervalDays
        +Double easeFactor
        +Int lapseCount
        +List tags
        +Long lastUpdated
    }
    class IngestionJob {
        +String jobId
        +ContentProcessingStatus status
        +ImmersionContent content
        +String error
    }
    class SpacedRepetitionAlgorithm {
        <<interface>>
        +calculateNextReview(word, quality) VocabularyWord
    }
    class Sm2Algorithm
    ImmersionContent "1" *-- "0..*" SubtitleLine
    VocabularyWord "0..*" --> "0..1" ImmersionContent : source reference
    VocabularyWord "0..*" --> "0..1" SubtitleLine : context reference
    IngestionJob --> "0..1" ImmersionContent : READY result
    SpacedRepetitionAlgorithm <|.. Sm2Algorithm
    SpacedRepetitionAlgorithm ..> VocabularyWord
```

Ссылки VocabularyWord nullable. На устройстве без соответствующего видео оригинальные source IDs хранятся в metadata до импорта. Коллекция subtitles принадлежит content; удаление SQL cue обнуляет его ссылку в словаре. **Strategy** — SpacedRepetitionAlgorithm/Sm2Algorithm.

Исходники: [Models.kt](../shared/src/commonMain/kotlin/com/langkraft/domain/model/Models.kt), [SrsEngine.kt](../shared/src/commonMain/kotlin/com/langkraft/domain/srs/SrsEngine.kt).

## Клиент: use case, repositories и БД

```mermaid
classDiagram
    class LocalContentRepository {
        <<interface>>
        +getAllContent() Flow
        +getContentById(id) ImmersionContent
        +saveContent(content)
    }
    class RemoteContentSource {
        <<interface>>
        +fetchFromYouTube(url) ImmersionContent
    }
    class VocabularyRepository {
        <<interface>>
        +getWordsToReview() Flow
        +saveWord(word)
        +sync(cursor) Long
    }
    class IngestContentUseCase {
        +invoke(youtubeUrl) Result
    }
    class BackendRemoteSource
    class BaseSqlDelightRepository
    class SqlDelightContentRepository
    class SqlDelightVocabularyRepository
    class DatabaseHandle {
        +AppDatabase database
        +DatabaseAccess access
        +close()
    }
    class DatabaseAccess {
        -Mutex mutex
        +run(dispatcher, block)
    }
    class AppDatabase {
        <<generated>>
        +appDatabaseQueries
    }
    class UserSession {
        +restore()
        +signIn(email, password, displayName)
        +signOut()
    }
    class ISyncManager {
        <<interface>>
        +sync(force)
    }
    class SyncManager
    IngestContentUseCase --> RemoteContentSource
    IngestContentUseCase --> LocalContentRepository
    RemoteContentSource <|.. BackendRemoteSource
    LocalContentRepository <|.. SqlDelightContentRepository
    VocabularyRepository <|.. SqlDelightVocabularyRepository
    BaseSqlDelightRepository <|-- SqlDelightContentRepository
    BaseSqlDelightRepository <|-- SqlDelightVocabularyRepository
    BaseSqlDelightRepository --> DatabaseAccess
    DatabaseHandle *-- DatabaseAccess
    DatabaseHandle *-- AppDatabase
    DatabaseAccess --> AppDatabase
    UserSession --> DatabaseAccess
    ISyncManager <|.. SyncManager
    SyncManager --> VocabularyRepository
```

SQLDelight repositories реализуют **Repository**; BackendRemoteSource адаптирует HTTP. DatabaseHandle владеет driver, AppDatabase и единым access; DatabaseAccess сериализует обращения, а не создаёт отдельную БД для каждого repository. Диаграмма показывает композицию объектов приложения, не отдельные Gradle-модули.

Исходники: [Repositories.kt](../shared/src/commonMain/kotlin/com/langkraft/domain/repository/Repositories.kt), [data/repository](../shared/src/commonMain/kotlin/com/langkraft/data/repository), [data/database](../shared/src/commonMain/kotlin/com/langkraft/data/database), [data/sync](../shared/src/commonMain/kotlin/com/langkraft/data/sync).

## Player и владение offline-аудио

```mermaid
classDiagram
    class BaseViewModel {
        #CoroutineScope scope
        +onCleared()
    }
    class StateViewModel {
        +StateFlow state
        #updateState(updater)
    }
    class PlayerViewModel {
        +onEvent(event)
        +saveWord(word)
        +retryTranslation()
    }
    class PlayerLinguisticDelegate
    class OfflineDownloadDelegate
    class AudioDownloader {
        <<interface>>
        +downloadAudio(content) String
    }
    class AudioDownloaderImpl
    class AudioStorage {
        <<interface>>
        +store(contentId, source, expectedBytes) String
        +delete(location)
    }
    class FileAudioStorage
    class IndexedDbAudioStorage
    class AudioPlayer {
        <<interface>>
        +StateFlow currentTimeMs
        +StateFlow isPlaying
        +StateFlow error
        +load(url, requestId)
        +play()
        +pause()
        +seekTo(timeMs)
        +setPlaybackSpeed(speed)
        +release()
    }
    class AudioPlayerImpl
    class FfmpegAudioPlayer
    class AudioPlayerError {
        +Long requestId
        +AudioErrorReason reason
        +String message
    }
    BaseViewModel <|-- StateViewModel
    StateViewModel <|-- PlayerViewModel
    PlayerViewModel *-- PlayerLinguisticDelegate
    PlayerViewModel *-- OfflineDownloadDelegate
    PlayerViewModel --> AudioPlayer
    PlayerViewModel --> VocabularyRepository
    PlayerLinguisticDelegate --> LinguisticAssistant
    OfflineDownloadDelegate --> AudioDownloader
    OfflineDownloadDelegate --> LocalContentRepository
    AudioDownloader <|.. AudioDownloaderImpl
    AudioDownloaderImpl --> AudioStorage
    AudioDownloaderImpl --> DatabaseAccess
    AudioStorage <|.. FileAudioStorage
    AudioStorage <|.. IndexedDbAudioStorage
    AudioPlayer <|.. AudioPlayerImpl
    AudioPlayer <|.. FfmpegAudioPlayer
    AudioPlayer ..> AudioPlayerError
```

StateViewModel в коде generic `StateViewModel<S>`; Player использует PlayerState. AudioPlayerImpl — expect/actual с реализациями Web HTMLAudioElement и Desktop VLC; FfmpegAudioPlayer — JVM реализация на ffplay. Koin выбирает движок, исходники Android сохранены вне активной сборки. AudioPlayer/AudioStorage — платформенные порты и адаптеры. UI — **Presentation Model/MVVM**, делегаты разделяют операции, StateFlow — **Observer**.

Для LK-02 важны три владельца: storage до возврата location → downloader до SQL link → БД после link. Компенсация удаляет только новый объект без SQL-ссылок. В Web JS-модуль storage удерживает Web Lock на незавершённую загрузку; recoverIncompleteAudio удаляет manifest/chunks нового формата только при свободном lock. Запуск — WebDatabaseFactory и beginAudio. Готовые, legacy и no-lock объекты сохраняются; SQL recovery после аварии остаётся отдельной задачей. Связи с браузерными API показаны в [UML-ченджлоге REL-01](CHANGELOG_UML.md).

Исходники: [ui/player](../shared/src/commonMain/kotlin/com/langkraft/ui/player), [AudioPlayer.kt](../shared/src/commonMain/kotlin/com/langkraft/audio/AudioPlayer.kt), [AudioStorage.kt](../shared/src/commonMain/kotlin/com/langkraft/data/audio/AudioStorage.kt), [AudioDownloaderImpl.kt](../shared/src/commonMain/kotlin/com/langkraft/data/repository/AudioDownloaderImpl.kt).

## Backend: ingestion, auth, sync и AI

```mermaid
classDiagram
    class YouTubeIngestionService {
        +startIngestion(url) String
        +getJobStatus(jobId) IngestionJob
        +close()
    }
    class IngestionJobRepository {
        +save(job)
        +get(id) IngestionJob
        +update(id, modifier)
        +recoverInterrupted()
        +cleanup(expiryMillis)
    }
    class YtdlpClient {
        +getVideoInfo(url)
        +downloadContent(url, videoId, jobId)
        +discardJobFiles(jobId)
    }
    class ProcessRunner {
        <<interface>>
        +execute(arguments) String
    }
    class ExecutableProcessRunner
    class WaveformExtractor
    class SrtParser {
        <<object>>
        +parse(contentId, text) List
    }
    class AuthService {
        +register(request) AuthResponse
        +login(request) AuthResponse
    }
    class JwtService
    class BackendUserRepository
    class SyncService {
        +performSync(email, request) SyncResponse
    }
    class VocabularySyncRepository {
        <<interface>>
        +synchronize(userId, clientChanges, cursor) SyncResponse
    }
    class BackendVocabularyRepository
    class LinguisticAssistant {
        <<interface>>
        +translateWord(word, context) TranslationResult
        +translateSentence(text) String
        +analyzeSentence(text) DeepAnalysisResult
        +correctText(text) CorrectionResult
    }
    class MockLinguisticAssistant
    class GeminiLinguisticAssistant
    class CachingLinguisticAssistant
    YouTubeIngestionService --> YtdlpClient
    YouTubeIngestionService --> IngestionJobRepository
    YouTubeIngestionService --> WaveformExtractor
    YouTubeIngestionService ..> SrtParser
    YtdlpClient --> ProcessRunner
    WaveformExtractor --> ProcessRunner
    ProcessRunner <|.. ExecutableProcessRunner
    AuthService --> BackendUserRepository
    AuthService --> JwtService
    SyncService --> BackendUserRepository
    SyncService --> VocabularySyncRepository
    VocabularySyncRepository <|.. BackendVocabularyRepository
    LinguisticAssistant <|.. MockLinguisticAssistant
    LinguisticAssistant <|.. GeminiLinguisticAssistant
    LinguisticAssistant <|.. CachingLinguisticAssistant
    CachingLinguisticAssistant --> LinguisticAssistant : delegate
```

ProcessRunner — порт внешнего процесса, ExecutableProcessRunner — **Adapter** к ProcessBuilder с timeout/cleanup. IngestionService использует Channel и workers (**Producer–Consumer**). Cache — **Decorator**, AI-провайдеры — **Strategy**. Client commonModule выбирает mock, backendModule — обёрнутый кэшем Gemini/mock; это разные экземпляры/графы DI. Exposed Database удерживается backend repositories, SQLDelight AppDatabase к серверной БД не применяется.

Исходники: [backend](../backend/src/main/kotlin/com/langkraft/backend), [backend/ai](../backend/src/main/kotlin/com/langkraft/backend/ai), [backend/db](../backend/src/main/kotlin/com/langkraft/backend/db). Полный каталог паттернов, модулей и правил изменения — [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md).
