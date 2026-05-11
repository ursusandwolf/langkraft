# Changelog

## [Unreleased]

### Added
- **Backend Persistence:** Implemented real data storage using **Exposed ORM** and SQLite for users and vocabulary sync.
- **Sync Protocol:** Added a functional incremental synchronization logic in the backend.
- **Multiplatform UUID:** Implemented cross-platform UUID generator (`expect/actual`) to support JS/Web environments.
- **Backend Configuration:** Added `application.conf` for secure management of JWT secrets and server settings.

### Fixed
- **Code Review Fixes:**
    - Repaired `SqlDelightVocabularyRepository` (fixed broken `upsert`, implemented missing `sync`).
    - Fixed UI compilation errors in `ImmersionPlayerView`, `PlayerViewModel`, and `Theme`.
    - Removed hardcoded JWT secrets and фейковую авторизацию.
- **Build Stabilization:**
    - Replaced unavailable `YtdlpJava` with stable `youtubedl-java`.
    - Fixed missing Compose and Koin dependencies in `webApp`.
    - Resolved platform-specific `java.util` dependencies in the Shared module.
    - Temporarily disabled `androidApp` module and Android targets to allow builds in SDK-less environments.
    - Configured `mainClass` for Ktor to enable `shadowJar` builds.

### Added
- **User Authentication:** Implemented JWT-based authentication for the backend and secure API routes.
- **Cloud Sync Infrastructure:** Added a synchronization endpoint and logic for multi-device vocabulary updates.
- **Cloud Sync Foundation:** Updated SQLDelight schema with `lastUpdated` timestamps and incremental sync queries.
- **Auth & Sync Models:** Defined domain models for JWT authentication and multi-device synchronization.
- **Multi-platform Release Modules:** Dedicated `androidApp` and `webApp` modules for production builds.
- **Android Release Config:** Configured ProGuard and release build types for APK/Bundle generation.
- **Web Production Setup:** Implemented Compose HTML/Wasm entry point for production deployment.
- **Langkraft Design System:** New theme with "German Immersion" aesthetics (Midnight Blue & Amber palette).
- **Dark Mode Support:** Full support for dark theme across all components.
- **Enhanced AI Correction:** Improved pedagogical explanations and stylistic suggestions for "Active Writing".
- **Polished Writing UI:** Redesigned `WritingView` with better typography, card-based results, and "Clear" input capability.
- **Deep Analysis Mode:** Enhanced AI-driven grammatical breakdown with German-specific insights (cases, verb forms, syntax).
- **Contextual Lemmatization:** Ability to toggle base forms (lemmas) directly in the subtitle track.
- **Improved Deep Analysis UI:** Redesigned dialog with structured cards and syntax explanations.
- **Download Status Tracking:** Introduced `DownloadStatus` (IDLE, DOWNLOADING, COMPLETED, ERROR) to track offline media state.
- **Domain Exceptions:** Added `IngestionException` and `AiException` for structured error handling.
- **Backend Koin DI:** Transitioned backend service management to Koin.
- **Global Exception Handling:** Implemented Ktor `StatusPages` for consistent API error responses.
- **Playback Speed Control:** Added (0.75x, 1.0x, 1.25x, 1.5x) to `ImmersionPlayerView`.
- **Progress Tracking Dashboard:** Initial implementation of `DashboardView` and `DashboardViewModel`.
- **AI Active Writing:** Feature to write and get corrections with grammatical explanations.

### Changed
- **Backend Refactoring:**
    - `YtdlpClient` now uses `suspend` methods with `Dispatchers.IO` for non-blocking execution.
    - Extracted backend routing into modular extension functions (`apiRoutes`).
    - Improved `SrtParser` with robust timestamp parsing and detailed error logging.
- **Data Layer Optimization:**
    - Replaced inefficient `selectAll().find()` with targeted SQL queries (`selectContentById`).
    - Implemented database indexing for `SubtitleLine` and `Vocabulary` tables.
    - Refined `FileSystem` abstraction with platform-safe `resolve()` for path management.
    - Fixed type mismatches and cleaned up null-safety in repositories.
- **Offline Sync Improvements:**
    - Decoupled metadata ingestion from audio downloading in `IngestContentUseCase`.
    - Implemented "Safe Download" using temporary `.part` files to ensure file integrity.
    - Encapsulated playback URL logic within `ImmersionContent` domain model.
- **UI Refinement:**
    - Updated `ImmersionPlayerView` to display granular download states and error indicators.
    - Standardized ViewModel event handling and state updates in the Player module.
- Refactored `YouTubeIngestionService` to use `kotlinx-serialization` and real YouTube IDs.
- Refactored `SrsEngine` to use named constants for SM-2 algorithm.
- Replaced all wildcard imports with explicit imports across the project.
- Introduced **Koin DI** to the shared module.
- Implemented `BaseViewModel` to standardize `CoroutineScope` management.
- Provided `SqlDelightContentRepository` and `SqlDelightVocabularyRepository` implementations.
