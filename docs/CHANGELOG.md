# Changelog

## [Unreleased]

### Changed
- Refactored `YouTubeIngestionService` to use `kotlinx-serialization` and real YouTube IDs.
- Refactored `SrsEngine` to use named constants for SM-2 algorithm.
- Replaced all wildcard imports with explicit imports across the project.
- Refactored `SrtParser` for more robust timestamp parsing.
- Introduced **Koin DI** to the shared module.
- Implemented `BaseViewModel` to standardize `CoroutineScope` management.
- Provided `SqlDelightContentRepository` and `SqlDelightVocabularyRepository` implementations.

- `Application.kt` as the entry point for the backend server.
- `YouTubeIngestionService` refinement: added real metadata retrieval (title, duration) and Opus audio extraction.
- `SrtParser` improvements: added support for WEBVTT and improved robustness.
- Added JitPack repository to support `YtdlpJava`.
- Configured `:backend` module dependencies for Ktor and serialization.
- **Phase 4 & 5 Implementation:**
    - Implemented SRS Engine (SM-2 algorithm) for sentence-based learning.
    - Created `SrsTrainingView` and `SrsTrainingViewModel` for review sessions.
    - Implemented `ContentSelectionView` for YouTube import and library management.
    - Added **Repeat Loop Mode** to the immersion player.
    - Created **Prose Memorization Tool** for active recall practice.
    - Updated `VocabularyWord` model with SRS metadata.
    - Added `kotlinx-datetime` for KMP time management.

