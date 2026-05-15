# Changelog

## [Unreleased]
### Added
- **Unit Tests for SRS:** Added `Sm2AlgorithmTest` and `SrsTrainingViewModelTest` to cover critical learning logic and UI interactions.
- **LRU Cache for AI:** Implemented a thread-safe LRU cache (max 1000 entries) in `CachingLinguisticAssistant` to prevent memory leaks in the backend.
- **Ingestion Job Cleanup:** Added an hourly cleanup task in `YouTubeIngestionService` to remove ingestion jobs older than 24 hours.
- **Batch Vocabulary Retrieval:** Introduced `selectWordsByIds` query in SQLDelight to support high-performance batch operations.

### Changed
- **Content Repository Refactoring (ISP):** Split the monolithic `SqlDelightContentRepository` into three specialized components: `SqlDelightContentRepository` (local DB), `AudioDownloaderImpl` (media), and `BackendRemoteSource` (remote API).
- **Security Semantics:** Renamed `passwordHash` to `password` in `AuthRequest` and `RegisterRequest`. Backend now handles hashing raw passwords.
- **JSON Tags:** Switched vocabulary tags from CSV format to **JSON serialization** for better data integrity and support for commas in tags.
- **ViewModel Threading:** `BaseViewModel` now uses `Dispatchers.Main` by default and supports `CoroutineContext` injection for reliable unit testing.
- **SRS Precision:** Updated `Sm2Algorithm` to use `roundToInt()` for interval calculations, preventing systematic progress truncation.
- **Sync Concurrency:** Refactored `SyncManager` to hold the `Mutex` for the entire duration of the sync operation, resolving a potential race condition.
- **Reactive Player UI:** `PlayerViewModel` now reactively observes `AudioPlayer` state, ensuring synchronized playback and looping logic.

### Fixed
- **Serialization Bug:** Added missing `@Serializable` to `ImmersionContent` and related enums, fixing the READY state response in the ingestion API.
- **Hardcoded URLs:** Removed `localhost:8080` from repositories; the backend URL is now configurable via Koin `AppConfig`.
- **N+1 Query (Sync):** Optimized client-side sync by using batch retrieval of updated words.
- **Dashboard Test Failure:** Fixed `DashboardViewModelTest` by injecting `UnconfinedTestDispatcher`.

## [0.5.0] - 2026-05-13
### Added
... (rest of the file remains same)
