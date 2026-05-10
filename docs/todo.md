# Langkraft Development Roadmap

## Phase 1: Foundation (Current)
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

## Phase 3: AI & Intelligence
- [ ] Integrate Google Gemini API for translations
- [ ] **Deep Analysis Mode:** Grammatical breakdown of a specific subtitle line (cases, verb forms, syntax)
- [ ] **Contextual Lemmatization:** Link words to their base forms while keeping the sentence context
- [ ] **AI Correction:** Feature to correct user's "Active Writing" (diaries/summaries) based on content

## Phase 4: Learning Experience
- [ ] **Spaced Repetition (SRS):** Algorithm for vocabulary (sentences, not isolated words)
- [ ] **Repeat Loop Mode:** Easy toggle to loop a specific audio/subtitle segment for "Deep Listening"
- [ ] **Prose Memorization Tool:** Hidden-text mode for practicing memorized fragments
- [ ] **Active Output:** Simple text editor to write summaries of listened content
- [ ] Progress tracking dashboard (listening hours, sentences mastered)
- [ ] Offline synchronization for Android

## Phase 5: UI/UX Polishing
- [ ] Content selection screen (URL input, library)
- [ ] Theming and design system (German immersion aesthetics)
- [ ] Waveform visualization for audio
- [ ] Speed control (0.75x, 1x, 1.25x)

## Phase 6: Release & Scale
- [ ] Android APK/Bundle build
- [ ] Web production deployment (Compose HTML/Wasm)
- [ ] User authentication and cloud sync
