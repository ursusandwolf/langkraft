# Documentation

## Backend Architecture

The backend is built with **Ktor** and is responsible for content ingestion, user authentication, and serving media files.

### Components

- **`Application.kt`**: The server entry point. Uses **Koin** for dependency injection and **StatusPages** for global exception handling.
- **`Routing.kt`**: Modular routing configuration. All API endpoints are prefixed with `/api`.
- **`JwtService`**: Encapsulates JWT generation and validation logic.
- **`YtdlpClient`**: A `suspend`-friendly wrapper around `yt-dlp`. Executes long-running processes on `Dispatchers.IO`.
- **`YouTubeIngestionService`**: Orchestrates content fetching and metadata extraction.
- **`SrtParser`**: Robust SRT/VTT parser with strict timestamp validation and deterministic ID generation.

## Security

- **Password Hashing:** All user passwords are hashed using **BCrypt** (cost factor 12) before storage.
- **API Protection:** JWT-based authentication is required for all `/api/*` routes (except `/api/auth/*`).
- **AI Security:** Gemini API keys are never exposed in URLs; they are passed via secure HTTP headers (`x-goog-api-key`).

## Data Layer & IO

- **SQLDelight**: Local SQLite persistence with optimized indexes for subtitles and vocabulary review.
- **Repositories (Interface Segregation):** 
    - `LocalContentRepository`: Manages local storage of media content.
    - `RemoteContentSource`: Interface for fetching new content from external sources (YouTube).
    - `AudioDownloader`: Handles the background download of media files for offline use.
    - `SqlDelightVocabularyRepository`: Manages words, status counts, and SRS state.
- **`FileSystem`**: A multiplatform abstraction for file operations. Supports platform-safe path resolution via `resolve(base, child)`.

## Offline Synchronization

The app implements a robust offline-first strategy:
1. **Metadata Ingestion:** Metadata and subtitles are saved immediately upon import.
2. **Safe Download:** Audio is downloaded as a `.part` file. Upon completion, it is moved to the final destination and the `DownloadStatus` is updated to `COMPLETED`.
3. **Playback Resolution:** The `ImmersionContent.getPlaybackUrl()` method automatically prefers local files if the download is successful and the file exists.

## AI Integration

Uses **Google Gemini 1.5 Flash** for linguistic tasks. Implementation features:
- **`CachingLinguisticAssistant`**: A Decorator pattern implementation that caches results for translations, analyses, and corrections in memory (ConcurrentHashMap) to minimize API latency and costs.
- **Contextual Translation:** Translates words based on the sentence they appear in.
- **Deep Analysis Mode:** Dissects grammar and syntax of complex German sentences.
- **Contextual Lemmatization:** Identifies base forms (lemmas) for all words in a sentence.
- **Text Correction:** Corrects user's active writing with pedagogical explanations.

## UI & ViewModel Architecture

The project uses **Compose Multiplatform** for the UI and a custom `BaseViewModel` for state management.
- **`BaseViewModel`**: Manages a `CoroutineScope` tied to the lifecycle of the ViewModel.
- **Koin DI**: Used for dependency injection in the shared module. Initialized via `initKoin`.

## Learning Methodology

The app implements the **Ilis Immersion Method**:
- **SRS Engine:** Pluggable `SpacedRepetitionAlgorithm` interface. Default implementation is `Sm2Algorithm`, which implements the SuperMemo-2 algorithm with named pedagogical constants.
- **Repeat Loop:** Player feature for rhythmic immersion.
- **Memorization Tool:** Interactive UI for memorizing prose by hiding words.

## Quality Standards

A comprehensive code review was conducted on May 10, 2026. Key identified standards:
- **Type Safety:** Prefer typed JSON parsing over Regex.
- **DIP:** Inject dependencies (like `LinguisticAssistant`) into ViewModels using Koin.
- **SRS Constants:** Business logic coefficients should be named constants.
- **Explicit Imports:** Avoid wildcard imports as per project preferences.
