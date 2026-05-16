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
- **Security:** User passwords are secured with **BCrypt**. Client-side sends raw passwords in a field named `password`.
- **Architecture:** Strictly follows **Clean Architecture**. Introduced `StateViewModel` for standardized, boilerplate-free state management in all ViewModels. Data layer uses `BaseSqlDelightRepository` to encapsulate SQLDelight-to-Domain mapping logic.
- **Sync Protocol:** Refactored to use `SyncEntry` with explicit `UPSERT` and `DELETE` change types, replacing the previous `lapseCount = -1` hack. Backend now supports deletion processing.
- **Data Layer:** SQLDelight schema supports incremental sync and JSON-serialized tags. File system operations optimized with atomic `rename` for downloads.
- **Sync Manager:** Uses `Dispatchers.IO` (or `Dispatchers.Default` on common) to ensure UI responsiveness during sync.
- **Logging:** Unified multiplatform `Logger` replaces raw `println` calls in the data layer.
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
