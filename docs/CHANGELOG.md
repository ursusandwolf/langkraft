# Changelog

## [Unreleased]
### Added
- **Desktop Koin Module:** Created `DesktopModule` to provide platform-specific `AppDatabase` via `JdbcSqliteDriver`.
- **Ktor CIO Engine:** Added `ktor-client-cio` dependency for Desktop JVM.
- **Multiplatform Logger:** Introduced a unified `Logger` object to replace raw `println` calls.
- **FileSystem Rename:** Added `rename` support to `FileSystem` abstraction for atomic file operations.

### Changed
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
