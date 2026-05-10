# Changelog

## [Unreleased]

### Added
- Ktor backend implementation in `:backend` module.
- `Application.kt` as the entry point for the backend server.
- `YouTubeIngestionService` refinement: added real metadata retrieval (title, duration) and Opus audio extraction.
- `SrtParser` improvements: added support for WEBVTT and improved robustness.
- Added JitPack repository to support `YtdlpJava`.
- Configured `:backend` module dependencies for Ktor and serialization.
- **Phase 3 Infrastructure:** 
    - Expanded `LinguisticAssistant` interface in `:shared` with translation, deep analysis, and correction methods.
    - Implemented `GeminiLinguisticAssistant` in `:backend` using Google Gemini REST API.
    - Added AI endpoints to Ktor server: `/api/ai/translate-word`, `/api/ai/analyze-sentence`, `/api/ai/correct-text`.
    - Integrated Ktor Client in backend for external API calls.

