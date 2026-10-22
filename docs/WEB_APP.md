# Web client

Run `./gradlew.bat :webApp:jsBrowserDevelopmentRun` with the backend on port 8080; open http://127.0.0.1:3000. Node 22+ and JDK 21 are required. See the root README for complete setup and JWT configuration.

The Kotlin/JS IR + Compose Canvas application shares MainApp, view models, SRS, JWT session and sync with Desktop. The single entry point is webApp/src/jsMain/kotlin/Main.kt; index.html loads webApp.js. URL entry uses a native DOM input. The same origin /api requests are proxied to the backend during development.

Local SQLite WASM runs in a worker, persists through IndexedDB and uses async SQLDelight. Audio is streamed to IndexedDB and played from a Blob. Offline here means saved library/audio within a loaded application; no full offline application cache is configured.

New audio downloads hold a per-asset Web Lock until commit/abort. On database startup and before each download, recovery removes incomplete manifests and their chunks only after acquiring that asset's free lock. Active owners and complete audio are preserved. HTTPS or localhost is required for Web Locks; without the API, downloads still work but automatic cleanup is skipped. Legacy manifests and downloads created without locks are kept conservatively. See [Web Locks](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API) for the browser coordination contract.

A separate content lock spans the full SQL/HTTP/storage operation. At startup, AudioDownloadRecovery resets DOWNLOADING only when that lock is free and a known protocol marker exists in SyncMetadata: ERROR for an interrupted first download, COMPLETED with the previous offline path for a replacement. Active, legacy, unknown-protocol and no-lock owners are preserved. Complete-but-unlinked asset cleanup remains separate work. See [the recovery decision](ADR_AUDIO_DOWNLOAD_RECOVERY.md).

index.html loads browser-locale.js before Skiko/Compose to normalize POSIX locale tags rejected by Intl.Locale. Client linguistic features currently use MockLinguisticAssistant from commonModule. GEMINI_API_KEY enables the backend provider, not a remote AI implementation in the browser.

Complete environment/run instructions: [RUNNING.md](RUNNING.md). Module boundaries, DI and extension points: [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md); current UML: [UML_CLASSES.md](UML_CLASSES.md).

`npm test` in tools/web-storage-tests checks actual browser storage; `npm run test:app` checks async import, error/retry, playback, word/audio persistence, reload and offline playback. Build development and production in separate Gradle invocations. Production test bundle path: webApp/build/kotlin-webpack/js/productionExecutable. Tests use a deterministic local API fixture; `npm run test:youtube` is an opt-in real external backend check.
