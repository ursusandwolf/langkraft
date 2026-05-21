# Changelog

## [Unreleased]
### Added
- **Audio Waveform Extraction:** Implemented backend waveform generation using `ffmpeg` to extract normalized audio amplitudes for player visualization.
- **Persistent Backend Tests:** Stabilized backend repository tests using shared-memory SQLite configuration and JUnit5 Lifecycle management.

### Fixed
- **TUI Resource Cleanup:** Fixed memory leaks in TUI player by ensuring `onCleared()` is called for ViewModels.
- **Desktop DI Lifecycle:** Fixed ViewModel lifecycle management in Compose Desktop using `rememberViewModel` helper with `DisposableEffect`.
- **FileSystem Reliability:** Switched to `java.nio.file.Files.move` with atomic operations for safer file renames.
- **Sync Protocol Stability:** Fixed `SyncManager` throttling logic to correctly handle connectivity errors.

## [0.5.0] - 2026-05-13
### Added
... (rest of the file remains same)
