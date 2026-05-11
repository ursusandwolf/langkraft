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
- **Data Layer:** SQLDelight schema optimized with lemmas, SRS-data, and `lastUpdated` sync timestamps. Repositories support atomic transactions and incremental synchronization.
- **Backend:** Modularized Ktor server with Koin DI, JWT-based authentication, and a robust synchronization endpoint. Enhanced Gemini 1.5 Flash prompts for linguistic analysis.
- **AI Integration:** Full suite of immersion tools: contextual translation, Deep Analysis Mode (grammatical breakdown), contextual lemmatization, and pedagogical AI correction.
- **Multi-platform:** Dedicated modules for Android, Web (Wasm), and Desktop. Configured release build types and ProGuard for production readiness.
- **UI:** Langkraft Design System implemented with "German Immersion" aesthetics (Midnight Blue/Amber), supporting both Light and Dark modes.

## Tech Stack
...
## Pending Items
- [x] Deep Analysis Mode and Contextual Lemmatization
- [x] Pedagogical AI Correction for Active Writing
- [x] Theming and UI/UX Polishing (German aesthetics)
- [x] Multi-platform Release Configuration (Android & Web)
- [x] User authentication and cloud sync foundation
- [ ] Production deployment and user scaling

