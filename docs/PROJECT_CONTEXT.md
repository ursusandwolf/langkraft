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
- **Architecture:** Implemented **Interface Segregation (ISP)** for content management and **Dependency Inversion (DIP)** for SRS logic. Introduced the **Decorator Pattern** for AI response caching, improving performance and cost-efficiency.
- **Data Layer:** SQLDelight schema optimized with lemmas and SRS-data. Backend synchronization protocol optimized to resolve N+1 query issues.
- **Backend:** Modularized Ktor server with real persistence. Implements robust authentication and incremental vocabulary synchronization.
- **AI Integration:** Full suite of immersion tools with a caching layer: contextual translation, Deep Analysis Mode, contextual lemmatization, and pedagogical correction.
- **Multi-platform:** Shared UI and business logic supporting Android, Web (Wasm), and Desktop.
- **UI:** Langkraft Design System with "German Immersion" aesthetics (Midnight Blue/Amber). Player supports deep grammatical analysis and lemmatization.

## Tech Stack
...
## Pending Items
- [x] Deep Analysis Mode and Contextual Lemmatization
- [x] Pedagogical AI Correction for Active Writing
- [x] Theming and UI/UX Polishing (German aesthetics)
- [x] Multi-platform Release Configuration (Android & Web)
- [x] User authentication and cloud sync foundation
- [ ] Production deployment and user scaling

