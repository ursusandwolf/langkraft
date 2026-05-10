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
