# Project Context: langkraft

## Overview
A German immersion-first learning platform. Users learn through YouTube content with synchronized subtitles, contextual translations, and SRS-based vocabulary building.

## Current State
- **Data Layer:** SQLDelight schema implemented (Content, Subtitles, Vocabulary).
- **Domain Layer:** Core models and UseCases (IngestContent) defined.
- **Backend:** Ingestion service with `YtdlpJava` support (Opus + SRT).
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
