# Project Context: langkraft

## Overview
A German immersion-first learning platform. Users learn through YouTube content with synchronized subtitles, contextual translations, and SRS-based vocabulary building.

## Current State

- **Platform priority:** Web first. Android is deferred to the final stage; its Gradle target/plugin are opt-in via `-PenableAndroid=true`, and no SDK is required for default builds.
- **Data Layer:** Async SQLDelight repositories with shared connection access, a persistent SQLite/WASM worker backed by IndexedDB, and chunked browser audio storage. JVM factories and file storage remain available; Android verification is deferred.
- **Domain Layer:** Core models and UseCases (IngestContent) defined.
- **Backend:** Runnable Ktor service invoking yt-dlp directly (Opus + SRT + metadata), with ingestion and media routes. Each backend instance accepts at most two active imports by default (`MAX_CONCURRENT_IMPORTS`); overload returns 503 without queuing. Real YouTube import, Opus playback and HTTP Range verified on 2026-10-21. Node runtime is explicitly enabled for yt-dlp; Node 22+ is needed for ingestion. Netty disconnects cancel imports, the dev proxy propagates browser cancellation, and process cleanup waits for Windows file handles.
- **Audio:** Platform players (ExoPlayer/HTML5) implementing a shared interface and connected to PlayerViewModel. Errors carry a typed reason and load request ID. A missing/incomplete local Web object offers explicit online playback; stale errors are ignored, network retry keeps the selected remote source and SQLite references remain unchanged.
- **UI:** Runnable `webApp` with a library, native URL input, import/error/cancel flow, Compose player, and due-word list. Dev server on `127.0.0.1:3000` proxies `/api` to backend port 8080; set `PUBLIC_BASE_URL` to the Web origin. See [Web setup](WEB_APP.md).
- **Vocabulary:** Word lookup/save UI and SQLDelight persistence; due-word flows refresh on database changes and once per minute as time passes.
- **CI:** `.github/workflows/ci.yml` checks PRs and `main` with separate JVM/Web jobs, development/production fixture scenarios, Gradle/npm caches, test reports and screenshots. Local commands are in README; the first hosted JVM job passed, while Web dependency installation failed; Webpack/lock-file compatibility with npm 10 is fixed locally and a hosted rerun is pending.
- **Subtitles:** Strict SRT validation with line-specific errors, BOM/newline support and integer milliseconds. Cues are stably sorted by start time; overlaps are preserved. Schema 2 persists cue ordinals; migration from schema 1 preserves vocabulary links and SRS, with an ID fallback for legacy equal-start cues. Invalid files fail the whole import, remove its files and leave the existing library intact. VTT is converted by yt-dlp/FFmpeg before parsing. See [subtitle rules](SUBTITLES.md).

## Tech Stack
- **Language:** Kotlin
- **UI:** Compose Multiplatform
- **DB:** SQLDelight (SQLite)
- **AI:** LinguisticAssistant interface (Mock implemented)
- **Backend:** Ktor + yt-dlp

## Pending Items

Session handoff (2026-10-21): Web sprint 6 is complete: LK-06 MIME validation and LK-05 explicit online playback recovery are implemented and tested. Next work is sprint 7 (LK-02/LK-03 audio ownership, compensation and crash recovery), then sprint 8 multi-tab updates; sprint 9 storage budgets is mandatory before public hosting. Record every completed change in `CHANGELOG.md` and `docs/CHANGELOG_UML.md` under matching IDs, showing actual class changes and existing design patterns. The incoming report is `docs/CODE_REVIEW_REPORT.md`; implementation details are in `docs/CODE_REVIEW_FIX_PLAN.md`. Local ZIP review archives and screenshots are ignored build artifacts, not part of the committed sources. Review ZIPs based on 397d007 predate sprint 6; regenerate them before another review. Confirm hosted CI after pushing. Android SDK/device verification remains deferred.

The implementation order and acceptance criteria are tracked in the [sprint plan](todo.md). Sprints 1–3 and 5 are complete for Web. JVM regression now includes 75 tests (31 backend, 44 shared), all passing on 2026-10-21; sprint 5 also verified offline VTT conversion with real FFmpeg 9.0.2. After the review fixes, 34 Kotlin browser tests, three storage scenarios and development/production fixture interface scenarios passed, including actual app migration from schema 1 and reopening schema 2. The opt-in real YouTube check verifies an actual German-captioned video (156.2005s, 48 cues), IndexedDB playback, cue seeking, Range 206 and ffprobe; actual Web cancellation stops yt-dlp and leaves no files. See [YouTube verification](YOUTUBE_VERIFICATION.md) for versions, commands, negative cases and external dependencies. Sprint 4 CI is implemented; its first hosted JVM job passed; the Web npm 10 lock-file failure is fixed locally and a successful hosted rerun is pending. Next product work: real AI translation and SRS training. The backend disconnect regression uses a dedicated IO scope so blocking Netty shutdown cannot deadlock its parent test; it also has a 30-second JUnit timeout. Development and production Web bundles must be built in separate Gradle invocations because Kotlin 1.9 shares their compileSync output directory. Translation is a labeled mock; full offline page startup/PWA and canvas accessibility remain future work. Android follows the Web stages. See [data-layer setup](DATA_LAYER.md).

- [ ] Implement real Google Gemini AI integration
- [x] Create the Web application entry point and wire the implemented repositories
- [ ] Verify and package Android on the final stage, after the Web version
- [x] Create Content Selection screen
- [ ] Implement Spaced Repetition (SRS) training UI
- [x] Add "YouTube URL Paste" flow in UI
