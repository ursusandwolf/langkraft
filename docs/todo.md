# Langkraft Development Roadmap

## Phase 1: Foundation (Done)
- [x] Architecture design (KMP + Compose)
- [x] SQLDelight schema for Content and Vocabulary
- [x] Domain models and UseCases
- [x] Multiplatform Audio Player (expect/actual)
- [x] Basic Immersion Player UI

## Phase 2: Ingestion & Backend (Done)
- [x] Implement Ktor Backend using `YtdlpJava`
- [x] Robust SRT/VTT parsing logic
- [x] Audio streaming/downloading service (Opus format)
- [x] Error handling for YouTube extraction

## Phase 3: AI & Intelligence (Done)
- [x] Integrate Google Gemini API for translations (with `CachingLinguisticAssistant` Decorator)
- [x] **Deep Analysis Mode:** Grammatical breakdown of a specific subtitle line
- [x] **Contextual Lemmatization:** Link words to their base forms
- [x] **AI Correction:** Feature to correct user's "Active Writing"

## Phase 4: Learning Experience (Done)
- [x] **Spaced Repetition (SRS):** SM-2 algorithm integrated via Dependency Injection
- [x] **Repeat Loop Mode:** Easy toggle to loop a specific audio/subtitle segment
- [x] **Prose Memorization Tool:** Hidden-text mode
- [x] **Active Output:** Writing diaries
- [x] Progress tracking dashboard
- [x] **SRS UX Improvement:** Move from 0-5 scale to a 4-button Anki-style system (AGAIN, HARD, GOOD, EASY)
# Langkraft Development Roadmap

## Phase 5: UI/UX Polishing
- [x] Content selection screen (URL input, library)
- [x] Theming and design system (German aesthetics)
- [x] Speed control (0.75x, 1x, 1.25x)
- [x] Extract offline downloading logic from `PlayerViewModel` into a dedicated `OfflineDownloadDelegate`
- [x] TUI Player: Enable phrase segmentation and real-time active marker
- [x] TUI Player: Add navigation and playback controls
- [x] Implement Waveform amplitude extraction on backend to power `WaveformVisualizer` (currently simulated)
- [x] Refactor audio playback: Migrated from `ffplay` subprocess hack to `vlcj` for native seek/rate control
- [x] Implement automated cleanup for downloaded media files

- [x] Android APK/Bundle build configuration
- [x] Web production deployment setup (Compose HTML/Wasm)
- [x] User authentication (BCrypt hashing) and JWT routing
- [x] Change Ktor `/api/ingest` to be asynchronous (polling/webhooks) to avoid HTTP timeouts during long downloads

## Phase 7: Synchronization & Offline First (Done)
- [x] Backend incremental synchronization protocol (Optimized with Batch Upserts)
- [x] Implement database-backed Offline-First Queue (`PendingSyncChange`)
- [x] Refactor `SyncManager` to use `VocabularyRepository.sync()` instead of in-memory queue
- [x] Remove hardcoded sync URL from `SqlDelightVocabularyRepository`
- [x] Implement conflict resolution logic on backend (Last Write Wins based on `lastUpdated`)
- [x] Integrate `SyncManager` into `DashboardViewModel` to trigger sync on app start
