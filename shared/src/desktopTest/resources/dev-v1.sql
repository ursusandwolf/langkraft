-- Table for storing media content (YouTube videos, audio files)
CREATE TABLE Content (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    audioUrl TEXT NOT NULL,         -- Remote URL
    localAudioPath TEXT,            -- Local path for offline mode
    sourceUrl TEXT NOT NULL,        -- Original YouTube URL
    durationSeconds INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,     -- Timestamp
    downloadStatus TEXT NOT NULL DEFAULT 'IDLE'
);

-- Table for subtitle lines/cues
CREATE TABLE SubtitleLine (
    id TEXT NOT NULL PRIMARY KEY,
    contentId TEXT NOT NULL,
    startMs INTEGER NOT NULL,
    endMs INTEGER NOT NULL,
    originalText TEXT NOT NULL,     -- Original text in content language
    translationText TEXT,           -- Optional translation text
    FOREIGN KEY (contentId) REFERENCES Content(id) ON DELETE CASCADE
);

-- Table for vocabulary (saved words/phrases)
CREATE TABLE Vocabulary (
    id TEXT NOT NULL PRIMARY KEY,
    word TEXT NOT NULL,             -- The word itself (e.g., "Häuser")
    lemma TEXT,                     -- Dictionary form (e.g., "Haus")
    translation TEXT,               -- User or AI provided translation
    contextSentence TEXT,           -- The specific sentence where it was found
    contentId TEXT,                 -- Reference to source content
    subtitleLineId TEXT,            -- Reference to specific timestamp
    addedAt INTEGER NOT NULL,

    -- Spaced Repetition (SRS) data
    nextReviewAt INTEGER,           -- Timestamp for next review
    intervalDays INTEGER NOT NULL DEFAULT 0,
    easeFactor REAL NOT NULL DEFAULT 2.5,
    status TEXT NOT NULL DEFAULT 'NEW', -- NEW, LEARNING, MASTERED
    lapseCount INTEGER NOT NULL DEFAULT 0,
    tags TEXT NOT NULL DEFAULT '',
    lastUpdated INTEGER NOT NULL DEFAULT 0,

    FOREIGN KEY (contentId) REFERENCES Content(id) ON DELETE SET NULL,
    FOREIGN KEY (subtitleLineId) REFERENCES SubtitleLine(id) ON DELETE SET NULL
);


CREATE TABLE PendingSyncChange(wordId TEXT NOT NULL PRIMARY KEY, changeType TEXT NOT NULL, timestamp INTEGER NOT NULL);
CREATE TABLE SyncMetadata(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
