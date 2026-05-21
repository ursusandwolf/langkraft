# Project Context: langkraft

## Overview
A German immersion-first learning platform. Users learn through YouTube content with synchronized subtitles, contextual translations, and SRS-based vocabulary building.

## Methodology (Ilis Immersion)
The app follows a specific language acquisition methodology:
1. **Immersion via Listening:** Focus on rhythm and patterns before rules.
2. **Content Repetition:** Tools for looping segments and multiple re-listens.
3. **Sentence-Based Vocabulary:** No isolated word lists; everything is in context.
4. **Deep Analysis:** Using AI to dissect specific high-value texts.
5. **Active Output:** Writing diaries/summaries with AI-driven corrections.
6. **Memorization:** Features to help internalize corrected prose.

## Current State
- **Security:** User passwords are secured with **BCrypt**. Client-side sends raw passwords in a field named `password`. JWT validity is now configurable via `application.conf`.
- **Architecture:** Strictly follows **Clean Architecture**. Introduced `StateViewModel` for standardized state management. Backend route handlers are thin, delegating to services like `SyncService`.
- **Ingestion System:** `YouTubeIngestionService` uses a **worker queue** (Channel-based) to limit concurrent tasks. `IngestionJobRepository` is now **persistent** (Exposed/SQLite).
- **Sync Protocol:** Refactored to use `SyncEntry` with explicit `UPSERT` and `DELETE`. Improved data integrity by clearing pending changes only after server confirmation.
- **Data Layer:** SQLDelight schema supports incremental sync. Backend uses Exposed with SQLite.
- **Configuration:** All major constants (JWT, cleanup intervals, worker counts) are externalized in `application.conf`.
- **Logging:** Unified multiplatform `Logger` replaces raw `println` calls.
- **Testing:** Unit tests cover `Sm2Algorithm` and `SrsTrainingViewModel`.

## Tech Stack
- **Build**: Gradle 8.14
- **Language**: Kotlin 2.0.21
- **Platform**: Multi-platform (Android, Web/Wasm, Desktop)
- **UI**: Compose Multiplatform
- **Database**: SQLDelight (Client), Exposed/SQLite (Backend)
- **Ktor**: 2.3.5 (Client & Server)
- **DI**: Koin 3.5.0
- **AI**: Gemini API
- **Authentication**: JWT & BCrypt

## Pending Items
- [ ] Implement real Waveform extraction on backend using `audiowaveform` or `ffmpeg`
- [ ] Production deployment and user scaling
