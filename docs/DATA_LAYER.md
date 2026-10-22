# Data layer (schema v3)

The client uses asynchronous SQLDelight on a persistent JVM JDBC connection or a SQLite WASM worker backed by IndexedDB. DatabaseAccess serializes connection access. JVM migrations create a VACUUM INTO backup before upgrading; both historical dev and main schemas are accepted. Newer unknown schemas are rejected.

Content owns ordered SubtitleLine records. UPDATE/INSERT avoids REPLACE cascade deletion. Reimport retains completed offline audio and SRS links; obsolete subtitle references are cleared by foreign keys. Vocabulary retains interval, ease, lapses, tags and timestamps. Source references unavailable on another client are stored separately and restored after import.

PendingSyncChange uses monotonic local revisions. Acknowledgement clears only the exact sent revision; a stale response cannot overwrite a newer pending edit. Backend sync uses JWT identity, server cursor and delete tombstones. Due-word queries refresh every minute and on database changes.

AudioStorage streams into a temporary file or IndexedDB chunks. A durable location is returned only on complete validation. FileAudioStorage also cleans up if cancellation interrupts the dispatcher return after publication. AudioDownloaderImpl preserves the previous offline path, transfers ownership in a short cancellation-safe SQL link, and propagates cancellation without deleting an already linked asset. Failed-link compensation checks persisted references under DatabaseAccess before deleting; an unreadable commit outcome retains the asset for future recovery. Cleanup failures remain suppressed on the primary exception. General retention of confirmed or legacy audio remains separate work. Browser audio is read as a Blob and object URLs are revoked when switching sources.

New Web/Desktop/TUI protocols now recover SQL DOWNLOADING and pending audio under a free content lease. IndexedDB resolves manifest/chunks transactionally; JournaledFileAudioStorage removes its sidecar last for retry. Legacy/no-lock source data is preserved. An operational Python tool can audit a Desktop/TUI profile read-only or create a separate recovered SQLite snapshot with stable linked audio, preserving SRS/pending sync while clearing the copied auth token. Web recovery.html reads raw IDB snapshots readonly and repairs a new UUID profile; it exports/imports compact SQLite/audio JSON up to 64 MiB. Separate SQLite/audio snapshots do not form a cross-store atomic snapshot, so unavailable references become ERROR in the copy. Source remains unchanged, and profile database/file/download locks are isolated. See [LEGACY_RECOVERY](LEGACY_RECOVERY.md).

See README for paths, backup/restore and verification commands; INTEGRATION_RESOLUTION.md records the branch decisions.

Current class relationships and contribution rules: [UML_CLASSES.md](UML_CLASSES.md), [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md). The LK-02 query addition does not change schema v3.
