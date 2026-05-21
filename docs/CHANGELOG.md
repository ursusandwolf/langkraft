# Changelog

## [Unreleased]
### Added
- **Persistent Ingestion Jobs:** Migrated `IngestionJobRepository` from in-memory storage to a persistent SQLite table (`IngestionJobs`) using Exposed.
- **Ingestion Work Queue:** Implemented a `Channel`-based work queue in `YouTubeIngestionService` with a configurable number of concurrent workers (default 4) to prevent resource exhaustion.
- **SyncService:** Extracted synchronization logic from route handlers into a dedicated `SyncService`, improving separation of concerns.
- **UnauthorizedException:** Added a custom exception for authentication failures with a global handler in `StatusPages`.
- **External Configuration:** Moved JWT validity, ingestion cleanup intervals, and worker counts to `application.conf`.
- **IngestionJob Metadata:** Added `createdAt` and `lastUpdatedAt` fields to the `IngestionJob` domain model and database schema.

### Changed
- **StateViewModel:** Introduced a generic `StateViewModel<S>` abstract class to standardize state management across all ViewModels, significantly reducing boilerplate.
- **BaseSqlDelightRepository:** Added an abstract base class for SQLDelight repositories with helper functions for mapping queries to domain models.
- **Desktop Koin Module:** Created `DesktopModule` to provide platform-specific `AppDatabase` via `JdbcSqliteDriver`.
- **Ktor CIO Engine:** Added `ktor-client-cio` dependency for Desktop JVM.
- **Multiplatform Logger:** Introduced a unified `Logger` object to replace raw `println` calls.
- **FileSystem Rename:** Added `rename` support to `FileSystem` abstraction for atomic file operations.

### Changed
- **Sync Integrity:** Improved `SqlDelightVocabularyRepository` to clear pending changes only after successful server confirmation, preventing data loss.
- **Service Lifecycle:** Refactored `YouTubeIngestionService` to support external `CoroutineScope`, improving resource management on the backend.
- **Sync Resilience:** `SyncManager` now allows immediate retries on network failures, improving UX during intermittent connectivity.
- **Typification:** Transitioned from `String` to `io.ktor.http.Url` for all backend endpoint configurations across the shared and backend modules.
- **ViewModel Refactoring:** All ViewModels (`Dashboard`, `ContentSelection`, `Player`, `SrsTraining`, `Writing`) now inherit from `StateViewModel`.
- **Repository Refactoring:** `SqlDelightContentRepository` and `SqlDelightVocabularyRepository` now inherit from `BaseSqlDelightRepository`, resulting in cleaner and more concise data mapping logic.
- **Sync Protocol Refactoring:** Introduced `SyncEntry` with explicit `UPSERT`/`DELETE` change tracking. Removed the hacky `lapseCount = -1` deletion marker.
- **Backend Deletion Support:** `BackendVocabularyRepository` now processes `DELETE` entries in the sync request.
- **DI Initialization:** Refactored `initKoin` usage to ensure it's called before Compose initialization on Desktop.
- **Sync Performance:** Moved sync operations to `Dispatchers.IO` (or `Dispatchers.Default` on common) to avoid blocking the main thread.
- **Download Optimization:** `AudioDownloaderImpl` now uses atomic `rename` instead of double-writing file bytes, reducing memory and disk I/O.

### Fixed
- **Desktop Crash (DI):** Fixed `NoBeanDefFoundException` for `AppDatabase` on Desktop.
- **Desktop Crash (Ktor):** Fixed `No HTTP client engine found` error on JVM.
- **Redundant DI Overloads:** Cleaned up `initKoin` signatures to avoid ambiguity.
- **Sync Logging:** Replaced raw `println` in `SqlDelightVocabularyRepository` with `Logger.e`.

## [0.5.0] - 2026-05-13
### Added
... (rest of the file remains same)
