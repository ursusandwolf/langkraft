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
- [/] Integrate Google Gemini API for translations (Backend service implemented)
- [x] **Deep Analysis Mode:** Grammatical breakdown of a specific subtitle line (cases, verb forms, syntax)
- [x] **Contextual Lemmatization:** Link words to their base forms while keeping the sentence context
- [x] **AI Correction:** Feature to correct user's "Active Writing" (diaries/summaries) based on content with pedagogical explanations

## Phase 4: Learning Experience (Done)
- [x] **Spaced Repetition (SRS):** Algorithm for vocabulary (sentences, not isolated words)
- [x] **Repeat Loop Mode:** Easy toggle to loop a specific audio/subtitle segment for "Deep Listening"
- [x] **Prose Memorization Tool:** Hidden-text mode for practicing memorized fragments
- [x] **Active Output:** Simple text editor to write summaries of listened content
- [x] Progress tracking dashboard (listening hours, sentences mastered)
- [x] Offline synchronization for Android

## Phase 5: UI/UX Polishing
- [x] Content selection screen (URL input, library)
- [x] Theming and design system (German immersion aesthetics)
- [x] Waveform visualization for audio
- [x] Speed control (0.75x, 1x, 1.25x)

## Phase 6: Release & Scale
- [x] Android APK/Bundle build configuration
- [x] Web production deployment setup (Compose HTML/Wasm)
- [/] User authentication and cloud sync (Protocol designed, Schema updated)
