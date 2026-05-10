# Changelog

## [Unreleased]

### Added
- Ktor backend implementation in `:backend` module.
- `Application.kt` as the entry point for the backend server.
- `YouTubeIngestionService` refinement: added real metadata retrieval (title, duration) and Opus audio extraction.
- `SrtParser` improvements: added support for WEBVTT and improved robustness.
- Added JitPack repository to support `YtdlpJava`.
- Configured `:backend` module dependencies for Ktor and serialization.
