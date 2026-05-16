# UML Diagrams

## Backend Architecture (Refactored)

```mermaid
classDiagram
    class Application {
        +module()
    }
    class Routing {
        +apiRoutes(Route)
    }
    class YouTubeIngestionService {
        +ingest(url: String) ImmersionContent
        -cleanupOldJobs()
    }
    class YtdlpClient {
        +getVideoInfo(url: String) YtdlpInfo
        +downloadContent(url, videoId) List~File~
    }
    class SrtParser {
        +parse(contentId, content) List~SubtitleLine~
    }

    Application --> Routing
    Routing --> YouTubeIngestionService
    YouTubeIngestionService --> YtdlpClient
    YouTubeIngestionService --> SrtParser
```

## Repository Architecture (ISP)

```mermaid
classDiagram
    class LocalContentRepository {
        <<interface>>
        +getAllContent() Flow
        +getContentById(id)
        +saveContent(content)
        +getImmersionStats() Flow
    }
    class AudioDownloader {
        <<interface>>
        +downloadAudio(content)
    }
    class RemoteContentSource {
        <<interface>>
        +fetchFromYouTube(url)
    }
    class FileSystem {
        <<interface>>
        +writeBytes()
        +rename(from, to)
    }
    class BackendRemoteSource {
        +httpClient: HttpClient
        +baseUrl: String
    }
    class AudioDownloaderImpl {
        +httpClient: HttpClient
        +fileSystem: FileSystem
    }
    class SqlDelightContentRepository {
        +db: AppDatabase
    }
    class VocabularyRepository {
        <<interface>>
        +sync(lastSyncTimestamp)
        +getSyncMetadata(key)
        +setSyncMetadata(key, value)
    }

    SqlDelightContentRepository ..|> LocalContentRepository
    BackendRemoteSource ..|> RemoteContentSource
    AudioDownloaderImpl ..|> AudioDownloader
    AudioDownloaderImpl --> FileSystem
    SqlDelightVocabularyRepository ..|> VocabularyRepository
```

## Synchronization Flow (Sequence)

```mermaid
sequenceDiagram
    participant UI as DashboardViewModel
    participant SM as SyncManager
    participant VR as VocabularyRepository
    participant API as Backend (Ktor)
    
    UI->>SM: sync()
    SM->>SM: Check Throttle (1 min)
    SM->>SM: Mutex.lock()
    SM->>VR: getSyncMetadata("last_sync")
    VR-->>SM: timestamp
    SM->>SM: withContext(Dispatchers.IO)
    SM->>VR: sync(timestamp)
    VR->>VR: Collect SyncEntry (UPSERT/DELETE)
    VR->>API: POST /api/sync (SyncRequest)
    API->>API: Process SyncEntry (Atomic)
    API-->>VR: SyncResponse (Server SyncEntries)
    VR->>VR: Apply Server Changes (Transaction)
    VR-->>SM: New Timestamp
    SM->>VR: setSyncMetadata("last_sync", newTimestamp)
    SM->>SM: Mutex.unlock()
    SM-->>UI: Sync Completed
```

## AI Decorator with LRU Cache

```mermaid
classDiagram
    class LinguisticAssistant {
        <<interface>>
        +translateWord()
        +analyzeSentence()
    }
    class GeminiLinguisticAssistant {
        +apiKey: String
    }
    class CachingLinguisticAssistant {
        -delegate: LinguisticAssistant
        -wordCache: LRU_Cache
        -analysisCache: LRU_Cache
    }

    CachingLinguisticAssistant ..|> LinguisticAssistant
    GeminiLinguisticAssistant ..|> LinguisticAssistant
    CachingLinguisticAssistant --> LinguisticAssistant : delegate
```

## Spaced Repetition (SRS) Logic

```mermaid
classDiagram
    class SpacedRepetitionAlgorithm {
        <<interface>>
        +calculateNextReview(word, quality)
    }
    class Sm2Algorithm {
        +calculateNextReview()
        -roundToInt()
    }
    class SrsTrainingViewModel {
        -srsAlgorithm: SpacedRepetitionAlgorithm
        +submitResult(quality)
    }

    Sm2Algorithm ..|> SpacedRepetitionAlgorithm
    SrsTrainingViewModel --> SpacedRepetitionAlgorithm
```

## Domain Model Relationships

```mermaid
classDiagram
    class ImmersionContent {
        +String id
        +String title
        +String audioUrl
        +String localAudioPath
        +DownloadStatus downloadStatus
        +getPlaybackUrl() String
    }
    class DownloadStatus {
        <<enumeration>>
        IDLE
        DOWNLOADING
        COMPLETED
        ERROR
    }
    class SubtitleLine {
        +String id
        +Long startMs
        +Long endMs
        +String originalText
        +String translationText
    }
    class VocabularyWord {
        +String id
        +String word
        +List~String~ tags
        +Int lapseCount
        +Long nextReviewMs
        +Double easeFactor
        +WordStatus status
    }

    ImmersionContent "1" *-- "many" SubtitleLine
    ImmersionContent --> DownloadStatus
    VocabularyWord ..> SubtitleLine : context
    VocabularyWord ..> ImmersionContent : source
```
