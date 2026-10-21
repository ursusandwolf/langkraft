# Langkraft 🇩🇪

**Langkraft** (from German *Lang* + *Kraft* - long-term power/strength) is an immersion-first German language learning platform. Unlike traditional apps that focus on grammar drills, Langkraft centers the learning experience around **comprehensible input** and **listening**.

## 🚀 The Philosophy
- **Immersion First:** Language is acquired through interesting content, not memorized through rules.
- **Listening Centric:** The primary goal is to bridge the gap between "hearing noise" and "understanding meaning."
- **Context is King:** Every word you learn is tied to the exact audio fragment and sentence where you first encountered it.
- **Zero Friction:** Import content directly from YouTube and start learning instantly.

## 🛠 Tech Stack
- **Multiplatform UI:** [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) (Android & Web).
- **Core Logic:** Kotlin Multiplatform (KMP).
- **Database:** [SQLDelight](https://cashapp.github.io/sqldelight/) (SQLite) for offline-first persistence.
- **Audio Engine:** ExoPlayer (Android) & HTML5 Audio (Web), implementing the shared `AudioPlayer` interface.
- **Backend:** Ktor Server for content ingestion.
- **Media Extraction:** [yt-dlp](https://github.com/yt-dlp/yt-dlp) invoked directly with separate process arguments and a timeout.
- **AI Layer:** Google Gemini (planned) for contextual translations and grammar analysis.

## 🏗 Architecture
Langkraft follows **Clean Architecture** principles to ensure the learning logic remains independent of platforms and frameworks:

- **`:shared`**:
    - `commonMain`: Domain models, Repositories, UseCases, and Compose UI.
    - `androidMain`/`jsMain`: Platform-specific implementations (Audio Player, Drivers).
- **`:backend`**: Ktor service responsible for extracting Opus audio and SRT subtitles from YouTube.
- **`:webApp`**: Runnable browser app with a library, YouTube import, player and saved words for review. See [Web setup](docs/WEB_APP.md).
- The shared data layer uses async SQLDelight repositories. Web stores SQLite and chunked audio in IndexedDB and restores playable audio URLs after reloads. See [data-layer setup](docs/DATA_LAYER.md) for wiring, lifecycle and browser tests.
- **Platform priority:** Web first; Android is deferred to the final stage. The Android target/plugin are disabled by default, so Web development does not require an Android SDK.

## 🗺 Roadmap

The five sprints are: repository implementations, a runnable Web app, real YouTube import verification, CI, and subtitle parsing improvements. Sprints 1–3 are complete; the CI workflow is implemented, with its first hosted run pending. Subtitle robustness follows CI. Real import, Opus playback, HTTP Range and cancellation were verified on 2026-10-21; see [results and repeatable checks](docs/YOUTUBE_VERIFICATION.md). Android follows the Web stages. See [the sprint plan](docs/todo.md) for tasks, acceptance criteria and the remaining product roadmap.

## 🛠 Getting Started
1. Install JDK 21. The checked-in Gradle Wrapper downloads the pinned Gradle distribution.
2. Install Node.js 18 or later for Web tooling. No Android SDK is needed for the default build.
3. For real imports, use Node.js 22+ and put `yt-dlp`, `ffmpeg` and `ffprobe` on `PATH`. Alternatively, set `YT_DLP_EXECUTABLE` to the yt-dlp executable path. Windows users can run `./tools/media/Setup-MediaTools.ps1` for pinned local tools; see [setup and verification](docs/YOUTUBE_VERIFICATION.md).
4. Run the backend and Web app in separate terminals. On Windows:

```powershell
# Terminal 1: use the Web origin in generated media URLs.
$env:PUBLIC_BASE_URL = 'http://127.0.0.1:3000'
.\gradlew.bat :backend:run

# Terminal 2:
.\gradlew.bat :webApp:jsBrowserDevelopmentRun
```

Open **http://127.0.0.1:3000**. On macOS/Linux use `PUBLIC_BASE_URL=http://127.0.0.1:3000 ./gradlew :backend:run` and `./gradlew :webApp:jsBrowserDevelopmentRun`.

The backend listens on port 8080 by default. `PORT` changes the listening port; `DOWNLOADS_DIR` changes media storage. The Web dev server proxies `/api` to port 8080, including media downloads. `PUBLIC_BASE_URL` must match the Web origin above, so downloads use the proxy. For production, serve the Web bundle and proxy `/api` on one HTTPS origin; see [deployment notes](docs/WEB_APP.md).

POST JSON `{"url":"https://www.youtube.com/watch?v=VIDEO_ID"}` to `/api/ingest`. The response contains content metadata, subtitles and an absolute `.opus` audio URL. Imports require German subtitles; playlists are disabled. Invalid source URLs return 400; extraction failures return 502. `GET /api/media/{id}.opus` serves audio with HTTP Range support and returns 404 for unknown files.

Run the JVM regression tests and compile the Web library:

```shell
./gradlew :shared:desktopTest :backend:test :shared:compileKotlinJs
```

Browser storage checks, including reload persistence, are described in [data-layer setup](docs/DATA_LAYER.md).

## Continuous integration

[CI](.github/workflows/ci.yml) runs on pull requests, pushes to `main`, and manual dispatch. It uses Ubuntu 24.04, JDK 21 and Node.js 22. Separate jobs run JVM regression tests and Web compilation, Kotlin browser tests, storage checks, and the application scenario on both development and production bundles. Tests use local fixtures without YouTube requests or AI keys; dependency installation requires network access.

Gradle and npm dependencies are cached. HTML/XML test reports and browser screenshots are uploaded even after failures and retained for 14 days. Successful Web checks also upload the production application as `web-production`. Each failing command fails its job. Android remains deferred.

To run the same checks locally on Linux/macOS, install JDK 21 and Node.js 22, then run from the repository root:

```shell
bash ./gradlew --no-daemon --stacktrace :shared:desktopTest :backend:test
cd tools/web-storage-tests
npm ci
npx playwright install --with-deps chromium
export CHROME_BIN="$(node --input-type=module -e 'import { chromium } from "playwright"; console.log(chromium.executablePath())')"
cd ../..
bash ./gradlew --no-daemon --stacktrace :shared:compileKotlinJs :shared:compileTestKotlinJs :shared:jsBrowserTest
bash ./gradlew --no-daemon --stacktrace :webApp:jsBrowserDevelopmentWebpack
cd tools/web-storage-tests
npm test
WEB_APP_ARTIFACTS=build/app-development npm run test:app
cd ../..
bash ./gradlew --no-daemon --stacktrace :webApp:jsBrowserDistribution
cd tools/web-storage-tests
WEB_APP_BUNDLE=../../webApp/build/dist/js/productionExecutable WEB_APP_ARTIFACTS=build/app-production npm run test:app
```

On Windows PowerShell, replace `bash ./gradlew` with `.\gradlew.bat`. After installing Chromium in `tools/web-storage-tests`, set `$env:CHROME_BIN = node --input-type=module -e "import { chromium } from 'playwright'; console.log(chromium.executablePath())"`. Set `WEB_APP_BUNDLE` and `WEB_APP_ARTIFACTS` using `$env:NAME = 'value'` before the respective `npm run test:app` command. Paths are relative to `tools/web-storage-tests`. The default screenshot directory remains `build/app` when `WEB_APP_ARTIFACTS` is unset. See [Web setup](docs/WEB_APP.md) for the fixture scenario.

Android verification is deferred to the final stage. Then install Android SDK Platform 34 / Build Tools 34.0.0, configure `ANDROID_HOME` or `sdk.dir`, and opt in:

```shell
./gradlew -PenableAndroid=true :shared:compileDebugKotlinAndroid
```

In a platform app, construct `AudioPlayerImpl(context)` on Android or `AudioPlayerImpl()` in JS and pass it to `PlayerViewModel`. Send `LoadContent` when opening a content item. Call `PlayerViewModel.release()` when its owner is destroyed; this cancels its jobs and releases the audio engine without cancelling the caller's scope.

---
*Developed with ❤️ for language learners who want to finally understand German.*
