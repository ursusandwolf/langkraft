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
- **Data Layer:** SQLDelight schema optimized with lemmas, SRS-data, and `lastUpdated` sync timestamps. `SqlDelightVocabularyRepository` now correctly implements synchronization and atomic updates.
- **Backend:** Modularized Ktor server using `EngineMain` with `Exposed` ORM and SQLite for real persistence. Implements a robust JWT-based authentication and a real incremental synchronization protocol for vocabulary data.
- **AI Integration:** Full suite of immersion tools: contextual translation, Deep Analysis Mode, contextual lemmatization, and pedagogical AI correction.
- **Multi-platform:** Dedicated modules for Android, Web (Wasm), and Desktop. UI components in Shared module are synchronized with the latest state and AI capabilities.
- **UI:** Langkraft Design System implemented with "German Immersion" aesthetics (Midnight Blue/Amber). `ImmersionPlayerView` supports lemmatization and deep analysis visualization.

## Tech Stack
...
## Pending Items
- [x] Deep Analysis Mode and Contextual Lemmatization
- [x] Pedagogical AI Correction for Active Writing
- [x] Theming and UI/UX Polishing (German aesthetics)
- [x] Multi-platform Release Configuration (Android & Web)
- [x] User authentication and cloud sync foundation
- [ ] Production deployment and user scaling

