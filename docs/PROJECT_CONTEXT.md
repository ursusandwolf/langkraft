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
- **Security:** Critical vulnerabilities resolved. User passwords are secured with **BCrypt**. AI API keys are protected in headers. JWT logic is encapsulated in a dedicated service.
- **Architecture:** The project strictly follows **Clean Architecture** principles. Introduced **Interface Segregation (ISP)** for content management and **Dependency Inversion (DIP)** for SRS logic. ViewModels are kept lean via the **Delegate Pattern** (`PlayerLinguisticDelegate`, `OfflineDownloadDelegate`), and AI responses are optimized using the **Decorator Pattern**.
- **Data Layer:** SQLDelight schema is fully robust, supporting incremental sync, `lapseCount`, and tags. The N+1 query issue in synchronization is resolved via batch upserts.
- **Backend:** Modularized Ktor server with asynchronous YouTube ingestion (`/api/ingest` polling) and background processing for waveform generation (currently simulated). Conflict resolution (Last Write Wins) implemented in synchronization.
- **Offline-First Sync:** Implemented a `PendingSyncChange` persistent queue on the client. `SyncManager` is integrated with the UI (Dashboard) to bridge the gap between UI and Repository sync logic.
- **Multi-platform:** Shared UI and business logic supporting Android, Web (Wasm), and Desktop.
- **UI & UX:** Langkraft Design System implemented. The SRS module utilizes an Anki-style 4-button system (AGAIN, HARD, GOOD, EASY).

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
