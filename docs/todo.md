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

## Phase 4: Learning Experience
- [x] **Spaced Repetition (SRS):** SM-2 algorithm integrated via Dependency Injection
- [x] **Repeat Loop Mode:** Easy toggle to loop a specific audio/subtitle segment
- [x] **Prose Memorization Tool:** Hidden-text mode
- [x] **Active Output:** Writing diaries
- [x] Progress tracking dashboard
- [ ] **SRS UX Improvement:** Move from 0-5 scale to a 4-button Anki-style system (AGAIN, HARD, GOOD, EASY)
- [ ] Add `lapseCount` and `tags` tracking to `VocabularyWord` model

## Phase 5: UI/UX Polishing
- [x] Content selection screen (URL input, library)
- [x] Theming and design system (German immersion aesthetics)
- [x] Speed control (0.75x, 1x, 1.25x)
- [ ] Implement Waveform amplitude extraction on backend to power `WaveformVisualizer`
- [ ] Fix state race condition in `SrsTrainingViewModel` when updating DB and UI state concurrently

## Phase 6: Release & Security
- [x] Android APK/Bundle build configuration
- [x] Web production deployment setup (Compose HTML/Wasm)
- [x] User authentication (BCrypt hashing) and JWT routing
- [ ] Change Ktor `/api/ingest` to be asynchronous (polling/webhooks) to avoid HTTP timeouts during long downloads

## Phase 7: Synchronization & Offline First
- [x] Backend incremental synchronization protocol (Optimized with Batch Upserts)
- [ ] Implement `SyncManager` on the KMP client with an Offline-First Queue (`PendingSyncChange`) to remove `NotImplementedError`
- [ ] Extract offline downloading logic from `PlayerViewModel` into a dedicated `OfflineDownloadDelegate`
