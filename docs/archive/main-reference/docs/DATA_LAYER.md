# Data-layer setup

The first platform is Web. Android is deferred to the final stage and its Gradle target/plugin are disabled by default. JDK 21 and Node.js 18+ are sufficient for the default JVM/Web build.

## Wiring on Web

Create the database in a coroutine and share the handle's access object across repositories:

```kotlin
val handle = createWebDatabase()
val client = createContentHttpClient(Js)
val api = ContentApi(client, backendBaseUrl)
val storage = IndexedDbAudioStorage()
val contents = SqlDelightContentRepository(
    handle.access, api, storage, { Date.now().toLong() }
)
val vocabulary = SqlDelightVocabularyRepository(
    handle.access, { Date.now().toLong() }
)
val ingest = IngestContentUseCase(contents)
```

`Js` is the Ktor JS engine; `Date` is `kotlin.js.Date`. The `webApp` module wires these services using `window.location.origin`. Its development server proxies `/api` to backend port 8080; set `PUBLIC_BASE_URL=http://127.0.0.1:3000` on the backend. See [Web setup](WEB_APP.md) for launch commands and production proxy configuration.

Stop collectors and outstanding calls before closing `client` and `handle`. The client owns its HTTP engine; the handle owns its SQLite worker. Repositories do not close these shared resources. Always share `handle.access`: it serializes operations on the single worker connection so unrelated coroutines cannot share a partially written transaction.

## Browser storage

- SQLDelight generates an asynchronous API. Queries and schema operations are awaited; JDBC tests use the same generated API.
- A module worker runs SQLite/WASM using [wa-sqlite](https://github.com/rhashimoto/wa-sqlite) and its IndexedDB VFS. SQLite pages, transactions and schema version persist in IndexedDB. Foreign keys are enabled on each connection.
- The SQLDelight adapter completes rollback even when the calling coroutine has been cancelled. Its message client removes completed requests and rejects outstanding requests when the worker fails or closes.
- Audio is written to a separate IndexedDB database in chunks. Incomplete entries stay hidden; an error or cancellation removes the download's manifest and chunks.
- Only a successful, nonempty download with the expected length is published as an `indexeddb-audio:` location. This identifier survives reloads; temporary Blob URLs are never stored in SQLite.
- The HTML audio player resolves that identifier when loading, applies pending seek/play commands after reading the blob, and revokes its Blob URL when changing tracks or releasing the player.
- Browser storage belongs to the current origin. Browser quotas, clearing site data and storage eviction can remove cached data; storage errors propagate to the existing error UI. No Android tooling is needed.

The local `@langkraft/web-storage` runtime package lives under `shared/src/jsMain/storage`; test tooling is separate under `tools/web-storage-tests`, so Yarn does not copy the headless browser or development dependencies into the application's dependency tree. Its runtime dependency is pinned in the package manifest. Gradle watches its runtime sources and refreshes Yarn's local dependency copy when they change. Production bundling must include module workers and WASM assets; the Webpack browser tests exercise both.

## Repository behavior

`getAllContent()` emits content with ordered subtitles through SQLDelight query notifications. `saveContent()` updates content and subtitles in one transaction; unchanged subtitle IDs retain word links, removed subtitles clear only their subtitle references, and IDs owned by other content are rejected.

Schema version 2 adds `SubtitleLine.ordinal`, populated from the input list on each save. Both library and player queries order by `startMs, ordinal, id`, so equal-start cues retain their input order after updates and reopening. The version 1 migration adds the column without rebuilding tables or changing vocabulary/SRS data. Old cues receive ordinal 0 and use ID as a deterministic fallback; their original equal-start ordering cannot be recovered from the stored data. Saving content again records the supplied order.

`saveWord()` preserves the review schedule and original added timestamp. `getWordsToReview()` observes writes and reevaluates due dates every minute while collected. The clock and dispatcher are injectable for tests.

`ContentApi` posts JSON to `/api/ingest`, resolves audio links against the backend, and discards server-local paths. Non-success responses raise `ContentApiException`; cancellation propagates. Its HTTP client permits the backend's extraction timeout.

`IngestContentUseCase` saves remote metadata, downloads audio, then saves the local location. If audio fails, remote content and subtitles remain available. Ordinary failures return `Result.failure`; cancellation is rethrown.

## JVM support and deferred Android

For JVM tests/tools use `createJvmDatabase(File(...))`, the Ktor `CIO` engine and `FileAudioStorage`. The factory creates or migrates a versioned database and enables foreign keys. File storage publishes completed downloads by rename and removes partial files on failure/cancellation.

Android sources remain available for the final stage. With an installed SDK, enable them using `-PenableAndroid=true`. The Android factory adapts the async schema for Android's synchronous driver. Android build/device verification remains deferred; it is not a prerequisite for finishing Web sprint 1.

## Verification

JVM regression tests and JS compilation:

```shell
./gradlew :shared:desktopTest :backend:test :shared:compileKotlinJs :shared:compileTestKotlinJs
```

Install browser test tooling once:

```shell
cd tools/web-storage-tests
npm ci
npx playwright install chromium --only-shell
npm test
```

This downloads a headless browser rather than an Android SDK. `npm test` bundles and serves the real worker/WASM and checks SQLite/audio persistence after a page reload, forced worker termination, rollback, foreign keys and incomplete audio cleanup.

Run Kotlin repository/player tests in a real Chromium/Chrome browser by setting `CHROME_BIN` to its executable, then running `./gradlew :shared:jsBrowserTest` from the project root. For Playwright's downloaded browser, point it to `headless_shell.exe` on Windows or `headless_shell` on Linux under the Playwright browser cache. `PLAYWRIGHT_BROWSERS_PATH` can select a custom cache directory for both installation and tests; the default Windows cache is `%LOCALAPPDATA%/ms-playwright`.

The Kotlin browser suite includes common tests plus real SQLite worker repositories, reopening, foreign keys, cancellation rollback, isolation between coroutines, saved audio restoration and deferred seeking. Browser test configuration explicitly serves emitted worker/WASM files on Windows and Unix. JVM tests cover real SQLite, complete ingestion with mock HTTP, file storage, SRS preservation and reopening.
