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
    class RemoteContentSource {
        <<interface>>
        +fetchFromYouTube(url)
    }
    class AudioDownloader {
        <<interface>>
        +downloadAudio(content)
    }
    class SqlDelightContentRepository {
        +db: AppDatabase
        +httpClient: HttpClient
    }
    class VocabularyRepository {
        <<interface>>
        +sync(lastSyncTimestamp)
        +getSyncMetadata(key)
        +setSyncMetadata(key, value)
    }

    SqlDelightContentRepository ..|> LocalContentRepository
    SqlDelightContentRepository ..|> RemoteContentSource
    SqlDelightContentRepository ..|> AudioDownloader
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
    SM->>VR: getSyncMetadata("last_sync")
    VR-->>SM: timestamp
    SM->>VR: sync(timestamp)
    VR->>API: POST /api/sync (changes + timestamp)
    API->>API: Resolve Conflicts (LWW)
    API-->>VR: Server Changes + New Timestamp
    VR->>VR: Apply Changes (Transaction)
    VR-->>SM: New Timestamp
    SM->>VR: setSyncMetadata("last_sync", newTimestamp)
    SM-->>UI: Sync Completed
```

## Spaced Repetition (SRS) Logic

```mermaid
classDiagram
    class SpacedRepetitionAlgorithm {
        <<interface>>
        +calculateNextReview(word, quality)
    }
    class Sm2Algorithm {
        +MIN_EASE_FACTOR
        +SUCCESS_THRESHOLD
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
        +String lemma
        +String translation
        +String contextSentence
        +Long nextReviewMs
        +Double easeFactor
        +WordStatus status
    }

    ImmersionContent "1" *-- "many" SubtitleLine
    ImmersionContent --> DownloadStatus
    VocabularyWord ..> SubtitleLine : context
    VocabularyWord ..> ImmersionContent : source
```

## UI State Machine

```mermaid
stateDiagram-v2
    [*] --> Loading
    Loading --> Idle: Content Loaded
    Idle --> Playing: Play Event
    Playing --> Idle: Pause Event
    Playing --> Playing: Update Time
    Playing --> DeepAnalysis: Analysis Event
    DeepAnalysis --> Playing: Dismiss
    Playing --> Lemmatization: Toggle Lemma
    Lemmatization --> Playing: Toggle Lemma
    Playing --> WordDetails: Word Clicked
    WordDetails --> Playing: Save/Dismiss
```
