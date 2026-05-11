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
- **Data Layer:** SQLDelight schema optimized with lemmas and SRS-data. Repositories support atomic transactions and safe file IO.
- **Backend:** Modularized Ktor server with Koin DI, non-blocking asynchronous clients, and enhanced Gemini 1.5 Flash prompts for deep grammatical analysis.
- **AI Integration:** Google Gemini 1.5 Flash integrated for contextual translation, detailed grammatical analysis (Deep Analysis Mode), and contextual lemmatization.
- **Offline Sync:** Robust background downloading with file integrity checks.
- **UI:** Compose Multiplatform architecture featuring reactive ViewModels, waveform visualization, and interactive deep analysis tools.

## Tech Stack
...
## Pending Items
- [x] Implement AI Correction UI for "Active Writing"
- [x] Add Progress tracking dashboard
- [x] Offline synchronization for Android
- [x] Waveform visualization
- [x] Deep Analysis Mode and Contextual Lemmatization
- [x] Pedagogical AI Correction for Active Writing
- [x] Theming and UI/UX Polishing (German aesthetics)
- [x] Multi-platform Release Configuration (Android & Web)
- [ ] User authentication and cloud sync
