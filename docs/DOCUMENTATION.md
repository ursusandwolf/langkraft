# Documentation

## Backend Architecture

The backend is built with **Ktor** and is responsible for content ingestion, user authentication, and serving media files.

### Components

- **`Application.kt`**: The server entry point. Uses **Koin** for dependency injection and **StatusPages** for global exception handling.
- **`Routing.kt`**: Modular routing configuration. All API endpoints are prefixed with `/api`.
- **`JwtService`**: Encapsulates JWT generation and validation logic.
- **`YtdlpClient`**: A `suspend`-friendly wrapper around `yt-dlp`. Executes long-running processes on `Dispatchers.IO`.
- **`YouTubeIngestionService`**: Orchestrates content fetching and metadata extraction. Includes a background cleanup job for expired ingestion tasks.
- **`SrtParser`**: Robust SRT/VTT parser with strict timestamp validation and deterministic ID generation.

## Security

- **Password Hashing:** All user passwords are hashed using **BCrypt** (cost factor 12) before storage. The client sends raw passwords in the `password` field.
- **API Protection:** JWT-based authentication is required for all `/api/*` routes (except `/api/auth/*`).
- **AI Security:** Gemini API keys are never exposed in URLs; they are passed via secure HTTP headers (`x-goog-api-key`).

## Data Layer & IO

- **SQLDelight**: Local SQLite persistence with optimized indexes for subtitles and vocabulary review.
- **Repositories (Interface Segregation):** 
    - `LocalContentRepository`: Manages local storage of media content.
    - `AudioDownloader`: Handles the background download of media files for offline use.
    - `RemoteContentSource`: Interface for fetching new content from external sources (YouTube).
    - `SqlDelightVocabularyRepository`: Manages words, status counts, and SRS state.
- **`FileSystem`**: A multiplatform abstraction for file operations. Supports platform-safe path resolution via `resolve(base, child)`.

## Offline Synchronization

The app implements a robust offline-first strategy:
1. **Metadata Ingestion:** Metadata and subtitles are saved immediately upon import.
2. **Safe Download:** Audio is downloaded as a `.part` file. Upon completion, it is moved to the final destination and the `DownloadStatus` is updated to `COMPLETED`.
3. **Playback Resolution:** The `ImmersionContent.getPlaybackUrl()` method automatically prefers local files if the download is successful and the file exists.

## Synchronization Architecture

Langkraft uses a custom incremental synchronization protocol:
- **`SyncManager` (Shared):** Orchestrates the client-side sync flow.
    - **Throttling:** Implements a minimum 1-minute interval between automatic syncs (configurable).
    - **State Persistence:** Uses the `SyncMetadata` database table to persist `lastSyncTimestamp` across application sessions.
    - **Thread Safety:** Utilizes a `Mutex` held for the **entire duration** of the sync to manage concurrent attempts and UI state.
- **`SqlDelightVocabularyRepository` (Client):** Implements the `VocabularyRepository` interface.
    - **Pending Changes:** Tracks all local `UPSERT` and `DELETE` operations in a `PendingSyncChange` table.
    - **Performance Optimization:** Uses `selectWordsByIds` for batch retrieval of updated records, avoiding the N+1 query problem.
- **`BackendVocabularyRepository` (Server):** 
    - **Conflict Resolution:** Implements "Last Write Wins" (LWW) logic based on the `lastUpdated` timestamp.


## AI Integration

Uses **Google Gemini 1.5 Flash** for linguistic tasks. Implementation features:
- **`CachingLinguisticAssistant`**: A Decorator pattern implementation with an **LRU Cache** (max 1000 entries) to minimize API latency and costs while preventing memory leaks.
- **Contextual Translation:** Translates words based on the sentence they appear in.
- **Deep Analysis Mode:** Dissects grammar and syntax of complex German sentences.
- **Contextual Lemmatization:** Identifies base forms (lemmas) for all words in a sentence.
- **Text Correction:** Corrects user's active writing with pedagogical explanations.

## UI & ViewModel Architecture

The project uses **Compose Multiplatform** for the UI and a custom `BaseViewModel` for state management.
- **`BaseViewModel`**: Manages a `CoroutineScope` ties to the UI lifecycle (default `Dispatchers.Main`). Supports injecting a `CoroutineContext` for unit testing.
- **Delegate Pattern**: Complex ViewModels like `PlayerViewModel` delegate specific responsibilities (e.g., AI linguistic analysis) to specialized delegates.
- **Reactive UI**: ViewModels observe StateFlows from repositories and infrastructure (e.g., `AudioPlayer`) to maintain a single source of truth.

## Learning Methodology

The app implements the **Ilis Immersion Method**:
- **SRS Engine:** Pluggable `SpacedRepetitionAlgorithm` interface. Default implementation is `Sm2Algorithm`, which implements the SuperMemo-2 algorithm with **rounded interval calculations** for mathematical accuracy.
- **Repeat Loop:** Player feature for rhythmic immersion.
- **Memorization Tool:** Interactive UI for memorizing prose by hiding words.
