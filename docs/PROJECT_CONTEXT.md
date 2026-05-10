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
- **Backend:** Ktor server with `YouTubeIngestionService` using `YtdlpJava`. Supports Opus audio extraction, SRT/VTT parsing, and real metadata retrieval.
- **Audio:** Multiplatform player (ExoPlayer/HTML5) via expect/actual.
- **UI:** Immersion Player View with auto-scroll and word-click interactions.
- **Vocabulary:** Word lookup with context and translation persistence.

## Tech Stack
- **Language:** Kotlin
- **UI:** Compose Multiplatform
- **DB:** SQLDelight (SQLite)
- **AI:** LinguisticAssistant interface (Mock implemented)
- **Backend:** Ktor + yt-dlp

## Pending Items
- [ ] Implement real Google Gemini AI integration
- [ ] Create Content Selection screen
- [ ] Implement Spaced Repetition (SRS) training UI
- [ ] Add "YouTube URL Paste" flow in UI
- [ ] Implement `ContentRepository` network implementation for client
