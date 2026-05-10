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
- **Data Layer:** SQLDelight schema implemented (Content, Subtitles, Vocabulary).
- **Domain Layer:** Core models and UseCases (IngestContent) defined.
- **Backend:** Ktor server with `YouTubeIngestionService` using `YtdlpJava`. Supports Opus audio extraction, SRT/VTT parsing, and real YouTube ID tracking. Improved robustness with `kotlinx-serialization`.
- **AI Integration:** Google Gemini integration for contextual translations, deep grammatical analysis, and text correction.
- **Immersion Tools:** "Repeat Loop Mode" for deep listening and "Prose Memorization Tool" for active recall implemented in the player.
- **Learning Experience:** SRS Engine (SM-2 with clean constants) and Training UI for context-based sentence review.
- **Content:** Library and YouTube import screen implemented.
- **Audio:** Multiplatform player (ExoPlayer/HTML5) via expect/actual.

## Tech Stack
- **Language:** Kotlin
- **UI:** Compose Multiplatform
- **DB:** SQLDelight (SQLite)
- **AI:** Google Gemini 1.5 Flash via LinguisticAssistant
- **Backend:** Ktor + yt-dlp

## Pending Items
- [ ] Implement AI Correction UI for "Active Writing"
- [ ] Add Progress tracking dashboard
- [ ] Offline synchronization for Android
- [ ] Theming and UI/UX Polishing (German aesthetics)
- [ ] Waveform visualization
