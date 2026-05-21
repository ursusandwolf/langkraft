# Changelog

## [Unreleased]
### Added
- **SRS Re-learning:** Implemented a "re-learning" phase in `SrsTrainingViewModel` where incorrectly answered words are moved to the end of the current session queue for immediate repetition.
- **SRS Session Testing:** Added comprehensive unit tests for `SrsTrainingViewModel` covering the entire session lifecycle, including queue transitions and re-learning logic.

### Changed
- **SRS Queue Management:** Refactored `SrsTrainingViewModel` to manage the review queue locally during a session, preventing UI flickering and unexpected list resets caused by background database updates.
- **Writing Practice Robustness:** `WritingViewModel` now automatically clears old AI corrections and error messages when the user modifies the input text, ensuring UI consistency.
- **Sync Throttling:** Improved `SyncManager` to respect the minimum synchronization interval even in the event of network or server errors, preventing aggressive retry loops.
- **SRS Event Pattern:** Migrated `SrsTrainingViewModel` to a clean `onEvent` pattern for UI actions (`ShowAnswer`, `SubmitResult`).

### Fixed
- **SRS UI Jitter:** Fixed a bug where saving a word would trigger a repository update that reset the current review session state.
- **TUI Compatibility:** Updated `CliMain.kt` to match the refactored `SrsTrainingViewModel` API.

## [0.5.0] - 2026-05-13
### Added
... (rest of the file remains same)
