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
- **Security:** User passwords are secured with **BCrypt**. Client-side sends raw passwords in a field named `password` (renamed from `passwordHash` to avoid ambiguity).
- **Architecture:** Strictly follows **Clean Architecture**. Content management is split into `LocalContentRepository` (SQLDelight), `AudioDownloader`, and `RemoteContentSource` (Backend API). Hardcoded URLs are removed; backend URL is now configurable via Koin.
- **Data Layer:** SQLDelight schema supports incremental sync and JSON-serialized tags. N+1 query issue in synchronization is resolved via batch retrieval (`selectWordsByIds`).
- **Backend:** Modularized Ktor server with asynchronous YouTube ingestion. `YouTubeIngestionService` now includes an hourly cleanup task for expired jobs. `CachingLinguisticAssistant` uses an LRU cache (max 1000 entries) to prevent memory leaks.
- **Player:** `PlayerViewModel` is reactively connected to `AudioPlayer`, ensuring synchronized state for playback and looping.
- **SRS:** SM-2 algorithm uses proper rounding (`roundToInt`) for interval calculations. Covered with unit tests.
- **Testing:** Introduced unit tests for `Sm2Algorithm` and `SrsTrainingViewModel` with fake repositories and test dispatchers.

## Tech Stack
- **Build**: Gradle 8.14
- **Language**: Kotlin 2.0.21
- **Platform**: Multi-platform (Android, Web/Wasm, Desktop)
- **UI**: Compose Multiplatform
- **Database**: SQLDelight
- **Backend**: Ktor
- **AI**: Gemini API (via `GeminiLinguisticAssistant`)
- **Authentication**: JWT & BCrypt

## Pending Items
- [ ] Implement real Waveform extraction on backend using `audiowaveform` or `ffmpeg`
- [ ] Production deployment and user scaling
