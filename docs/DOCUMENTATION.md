# Documentation

## Backend Architecture

The backend is built with **Ktor** and is responsible for content ingestion and serving media files.

### Components

- **`Application.kt`**: The Ktor server entry point. Defines routes:
    - `POST /api/ingest?url={url}`: Triggers the ingestion process.
    - `GET /api/media/{file}`: Serves downloaded audio files and subtitles.
- **`YouTubeIngestionService`**: Orchestrates `yt-dlp` to download audio (Opus format), subtitles (SRT/VTT), and metadata.
- **`SrtParser`**: Parses SRT and VTT files into the shared `SubtitleLine` domain model.

### Ingestion Flow

1. User provides a YouTube URL.
2. `YouTubeIngestionService` calls `yt-dlp` with `--extract-audio`, `--write-auto-sub`, and `--write-info-json`.
3. Metadata is extracted from the generated `.info.json`.
4. Subtitles are parsed by `SrtParser`.
5. An `ImmersionContent` object is returned with URLs pointing to the Ktor media endpoints.

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
