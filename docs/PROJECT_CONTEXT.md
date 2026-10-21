# Project Context: langkraft

## Overview
A German immersion-first learning platform. Users learn through YouTube content with synchronized subtitles, contextual translations, and SRS-based vocabulary building.

## Current State

- **Platform priority:** Web first. Android is deferred to the final stage; its Gradle target/plugin are opt-in via `-PenableAndroid=true`, and no SDK is required for default builds.
- **Data Layer:** Async SQLDelight repositories with shared connection access, a persistent SQLite/WASM worker backed by IndexedDB, and chunked browser audio storage. JVM factories and file storage remain available; Android verification is deferred.
- **Domain Layer:** Core models and UseCases (IngestContent) defined.
- **Backend:** Runnable Ktor service invoking yt-dlp directly (Opus + SRT + metadata), with ingestion and media routes. Real YouTube import, Opus playback and HTTP Range verified on 2026-10-21. Node runtime is explicitly enabled for yt-dlp; Node 22+ is needed for ingestion. Netty disconnects cancel imports, the dev proxy propagates browser cancellation, and process cleanup waits for Windows file handles.
- **Audio:** Platform players (ExoPlayer/HTML5) implementing a shared interface and connected to PlayerViewModel.
- **UI:** Runnable `webApp` with a library, native URL input, import/error/cancel flow, Compose player, and due-word list. Dev server on `127.0.0.1:3000` proxies `/api` to backend port 8080; set `PUBLIC_BASE_URL` to the Web origin. See [Web setup](WEB_APP.md).
- **Vocabulary:** Word lookup/save UI and SQLDelight persistence; due-word flows refresh on database changes and once per minute as time passes.
- **CI:** `.github/workflows/ci.yml` checks PRs and `main` with separate JVM/Web jobs, development/production fixture scenarios, Gradle/npm caches, test reports and screenshots. Local commands are in README; the first hosted clean-checkout run is pending.
- **Subtitles:** Strict SRT validation with line-specific errors, BOM/newline support and integer milliseconds. Cues are stably sorted by start time; overlaps are preserved. Invalid files fail the whole import, remove its files and leave the existing library intact. VTT is converted by yt-dlp/FFmpeg before parsing. See [subtitle rules](SUBTITLES.md).

## Tech Stack
- **Language:** Kotlin
- **UI:** Compose Multiplatform
- **DB:** SQLDelight (SQLite)
- **AI:** LinguisticAssistant interface (Mock implemented)
- **Backend:** Ktor + yt-dlp

## Pending Items

The implementation order and acceptance criteria are tracked in the [sprint plan](todo.md). Sprints 1–3 and 5 are complete for Web. JVM regression now includes 62 tests (28 backend, 34 shared), all passing on 2026-10-21; sprint 5 also verified offline VTT conversion with real FFmpeg 9.0.2. The prior verification passed 23 Kotlin browser tests, three storage scenarios and development/production fixture interface scenarios; browser checks were not repeated for the backend-only subtitle change. The opt-in real YouTube check verifies an actual German-captioned video (156.2005s, 48 cues), IndexedDB playback, cue seeking, Range 206 and ffprobe; actual Web cancellation stops yt-dlp and leaves no files. See [YouTube verification](YOUTUBE_VERIFICATION.md) for versions, commands, negative cases and external dependencies. Sprint 4 CI is implemented; its first hosted clean-checkout run is pending. Next product work: real AI translation and SRS training. The backend disconnect regression uses a dedicated IO scope so blocking Netty shutdown cannot deadlock its parent test; it also has a 30-second JUnit timeout. Development and production Web bundles must be built in separate Gradle invocations because Kotlin 1.9 shares their compileSync output directory. Translation is a labeled mock; full offline page startup/PWA and canvas accessibility remain future work. Android follows the Web stages. See [data-layer setup](DATA_LAYER.md).

- [ ] Implement real Google Gemini AI integration
- [x] Create the Web application entry point and wire the implemented repositories
- [ ] Verify and package Android on the final stage, after the Web version
- [x] Create Content Selection screen
- [ ] Implement Spaced Repetition (SRS) training UI
- [x] Add "YouTube URL Paste" flow in UI
