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
- **Data Layer:** SQLDelight schema implemented (Content, Subtitles, Vocabulary). Analytical queries for Dashboard added.
- **Domain Layer:** Core models and UseCases defined. Repositories support statistics and offline synchronization.
- **Backend:** Ktor server refactored for robustness. `YtdlpClient` isolated for better process management. JSON API with DTOs.
- **AI Integration:** Google Gemini integration for contextual translations, deep grammatical analysis, and text correction.
- **Immersion Tools:** "Repeat Loop Mode" and "Prose Memorization Tool" fully integrated into the Writing/Correction flow.
- **Learning Experience:** SRS Engine and Dashboard with real-time statistics (Hours, Mastery, Reviews).
- **Offline Sync:** Android offline mode implemented. Audio files are downloaded locally and preferred during playback. Multiplatform `FileSystem` abstraction added.
- **Audio:** Multiplatform player (ExoPlayer/HTML5). Improved with local file support and interactive Waveform visualization.

## Tech Stack
- **Language:** Kotlin
- **UI:** Compose Multiplatform
- **DB:** SQLDelight (SQLite)
- **AI:** Google Gemini 1.5 Flash
- **Backend:** Ktor + yt-dlp

## Pending Items
- [x] Implement AI Correction UI for "Active Writing"
- [x] Add Progress tracking dashboard
- [x] Offline synchronization for Android
- [x] Waveform visualization
- [ ] Theming and UI/UX Polishing (German aesthetics)
