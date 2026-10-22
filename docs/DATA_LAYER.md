# Data layer (schema v3)

The client uses asynchronous SQLDelight on a persistent JVM JDBC connection or a SQLite WASM worker backed by IndexedDB. DatabaseAccess serializes connection access. JVM migrations create a VACUUM INTO backup before upgrading; both historical dev and main schemas are accepted. Newer unknown schemas are rejected.

Content owns ordered SubtitleLine records. UPDATE/INSERT avoids REPLACE cascade deletion. Reimport retains completed offline audio and SRS links; obsolete subtitle references are cleared by foreign keys. Vocabulary retains interval, ease, lapses, tags and timestamps. Source references unavailable on another client are stored separately and restored after import.

PendingSyncChange uses monotonic local revisions. Acknowledgement clears only the exact sent revision; a stale response cannot overwrite a newer pending edit. Backend sync uses JWT identity, server cursor and delete tombstones. Due-word queries refresh every minute and on database changes.

AudioStorage streams into a temporary file or IndexedDB chunks. A durable location is returned only on complete validation. A failed database link removes the newly published asset. Browser audio is read as a Blob and object URLs are revoked when switching sources.

See README for paths, backup/restore and verification commands; INTEGRATION_RESOLUTION.md records the branch decisions.
