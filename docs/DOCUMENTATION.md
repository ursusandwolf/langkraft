# Documentation

## Backend Architecture

The backend is built with **Ktor** and is responsible for content ingestion and serving media files.

### Components

- **`Application.kt`**: The server entry point. Uses **Koin** for dependency injection and **StatusPages** for global exception handling.
- **`Routing.kt`**: Modular routing configuration. All API endpoints are prefixed with `/api`.
- **`YtdlpClient`**: A `suspend`-friendly wrapper around `yt-dlp`. Executes long-running processes on `Dispatchers.IO`.
- **`YouTubeIngestionService`**: Orchestrates content fetching and metadata extraction.
- **`SrtParser`**: Robust SRT/VTT parser with strict timestamp validation.

## Data Layer & IO

- **SQLDelight**: Local SQLite persistence with optimized indexes for subtitles and vocabulary review.
- **Repositories**: 
    - `SqlDelightContentRepository`: Manages media content and offline synchronization.
    - `SqlDelightVocabularyRepository`: Manages words and SRS state.
- **`FileSystem`**: A multiplatform abstraction for file operations. Supports platform-safe path resolution via `resolve(base, child)`.

## Offline Synchronization

The app implements a robust offline-first strategy:
1. **Metadata Ingestion:** Metadata and subtitles are saved immediately upon import.
2. **Safe Download:** Audio is downloaded as a `.part` file. Upon completion, it is moved to the final destination and the `DownloadStatus` is updated to `COMPLETED`.
3. **Playback Resolution:** The `ImmersionContent.getPlaybackUrl()` method automatically prefers local files if the download is successful and the file exists.

### Setup

Requires `yt-dlp` to be installed and available in the system PATH.
The `downloads` directory is used for temporary storage of media files.

## AI Integration

Uses **Google Gemini 1.5 Flash** for linguistic tasks:
- **Contextual Translation:** Translates words based on the sentence they appear in.
- **Deep Analysis:** Dissects grammar and syntax of complex German sentences.
- **Text Correction:** Corrects user's active writing with pedagogical explanations.

## UI & ViewModel Architecture

The project uses **Compose Multiplatform** for the UI and a custom `BaseViewModel` for state management.
- **`BaseViewModel`**: Manages a `CoroutineScope` tied to the lifecycle of the ViewModel.
- **Koin DI**: Used for dependency injection in the shared module. Initialized via `initKoin`.

### Data Layer

- **SQLDelight**: Used for local persistence.
- **Repositories**: `SqlDelightContentRepository` and `SqlDelightVocabularyRepository` implement the domain interfaces, providing a clean separation between data and domain layers.

## Learning Methodology

The app implements the **Ilis Immersion Method**:
- **SRS Engine:** SM-2 algorithm focusing on sentences as learning units.
- **Repeat Loop:** Player feature for rhythmic immersion.
- **Memorization Tool:** Interactive UI for memorizing prose by hiding words.

## Quality Standards

A comprehensive code review was conducted on May 10, 2026. Key identified standards:
- **Type Safety:** Prefer typed JSON parsing over Regex.
- **DIP:** Inject dependencies (like `LinguisticAssistant`) into ViewModels using Koin.
- **SRS Constants:** Business logic coefficients should be named constants.
- **Explicit Imports:** Avoid wildcard imports as per project preferences.
