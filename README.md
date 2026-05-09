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
- **Audio Engine:** ExoPlayer (Android) & HTML5 Audio (Web) via `expect/actual`.
- **Backend:** Ktor Server for content ingestion.
- **Media Extraction:** [yt-dlp](https://github.com/yt-dlp/yt-dlp) integrated via a custom Java/Kotlin wrapper.
- **AI Layer:** Google Gemini (planned) for contextual translations and grammar analysis.

## 🏗 Architecture
Langkraft follows **Clean Architecture** principles to ensure the learning logic remains independent of platforms and frameworks:

- **`:shared`**:
    - `commonMain`: Domain models, Repositories, UseCases, and Compose UI.
    - `androidMain`/`jsMain`: Platform-specific implementations (Audio Player, Drivers).
- **`:backend`**: Ktor service responsible for extracting Opus audio and SRT subtitles from YouTube.
- **`:androidApp` / `:webApp`**: Thin platform wrappers.

## 🗺 Roadmap
1. **Phase 1 (Done):** Foundation - Shared DB, Domain, and synchronized UI Player.
2. **Phase 2:** Ingestion - Full YouTube to Opus/SRT pipeline.
3. **Phase 3:** AI Integration - Context-aware translations via Gemini.
4. **Phase 4:** SRS - Spaced Repetition System for vocabulary.

## 🛠 Getting Started
1. Clone the repository.
2. Ensure you have `yt-dlp` installed on your system (for the backend).
3. Open the project in **IntelliJ IDEA** or **Android Studio**.
4. Run `./gradlew :androidApp:installDebug` or `./gradlew :webApp:jsBrowserRun`.

---
*Developed with ❤️ for language learners who want to finally understand German.*
