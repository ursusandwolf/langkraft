import hashlib
from contextlib import contextmanager
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import recover_legacy_profile as recovery


@contextmanager
def database(path):
    connection = sqlite3.connect(path)
    try:
        with connection:
            yield connection
    finally:
        connection.close()


class LegacyProfileRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "audio").mkdir()
        self.destination = self.root / "recovered"
        self.database = self.source / "langkraft.db"
        with database(self.database) as connection:
            connection.executescript("""
                PRAGMA user_version=3;
                CREATE TABLE Content(id TEXT PRIMARY KEY,title TEXT,localAudioPath TEXT,downloadStatus TEXT);
                CREATE TABLE SubtitleLine(id TEXT PRIMARY KEY,contentId TEXT,originalText TEXT);
                CREATE TABLE Vocabulary(id TEXT PRIMARY KEY,intervalDays INTEGER,nextReviewAt INTEGER,tags TEXT);
                CREATE TABLE PendingSyncChange(wordId TEXT PRIMARY KEY,changeType TEXT,timestamp INTEGER);
                CREATE TABLE SyncMetadata(key TEXT PRIMARY KEY,value TEXT);
                INSERT INTO SubtitleLine VALUES ('cue','video','Hallo');
                INSERT INTO Vocabulary VALUES ('word',17,2000000000,'["tag"]');
                INSERT INTO PendingSyncChange VALUES ('word','UPSERT',100);
                INSERT INTO SyncMetadata VALUES ('auth_token','PRIVATE-TOKEN');
                INSERT INTO SyncMetadata VALUES ('sync_cursor','1234');
                INSERT INTO SyncMetadata VALUES ('audioXdownloadYlock:unrelated','keep');
            """)

    def content(self, identifier="video", status="DOWNLOADING", audio=None):
        with database(self.database) as connection:
            connection.execute("INSERT INTO Content VALUES (?,?,?,?)", (identifier, "Title", str(audio) if audio else None, status))

    def test_default_audit_does_not_change_rows_or_artifacts_and_never_prints_token(self):
        self.content()
        partial = self.source / "audio" / "old.mp3.part"
        partial.write_bytes(b"partial")
        report = recovery.audit_profile(self.source)
        self.assertEqual("unknown", report["stuck_downloads"][0]["owner_state"])
        self.assertEqual("partial_owner_unknown", report["artifacts"][0]["kind"])
        self.assertNotIn("PRIVATE-TOKEN", json.dumps(report))
        with database(self.database) as connection:
            self.assertEqual("DOWNLOADING", connection.execute("SELECT downloadStatus FROM Content").fetchone()[0])
        self.assertEqual(b"partial", partial.read_bytes())
        self.assertFalse(self.destination.exists())

    def test_clone_restores_status_paths_and_preserves_srs_cues_and_sync_queue(self):
        audio = self.source / "audio" / "old.opus"
        audio.write_bytes(b"saved-audio")
        self.content(audio=audio)
        self.content(identifier="empty")
        self.content(identifier="other", status="COMPLETED", audio=audio)
        with database(self.database) as connection:
            connection.execute("INSERT INTO SyncMetadata VALUES ('audio_download_lock:video','unknown-protocol')")
        report = recovery.clone_profile(self.source, self.destination)
        with database(self.destination / "langkraft.db") as clone, database(self.database) as original:
            self.assertEqual("DOWNLOADING", original.execute("SELECT downloadStatus FROM Content WHERE id='video'").fetchone()[0])
            self.assertEqual([("empty", None, "ERROR"), ("other", str(self.destination / "audio" / "old.opus"), "COMPLETED"),
                              ("video", str(self.destination / "audio" / "old.opus"), "COMPLETED")],
                             clone.execute("SELECT id,localAudioPath,downloadStatus FROM Content ORDER BY id").fetchall())
            for table in ["SubtitleLine", "Vocabulary", "PendingSyncChange"]:
                self.assertEqual(original.execute(f"SELECT * FROM {table}").fetchall(), clone.execute(f"SELECT * FROM {table}").fetchall())
            self.assertIsNone(clone.execute("SELECT value FROM SyncMetadata WHERE key='auth_token'").fetchone())
            self.assertEqual("PRIVATE-TOKEN", original.execute("SELECT value FROM SyncMetadata WHERE key='auth_token'").fetchone()[0])
            self.assertEqual([("audioXdownloadYlock:unrelated", "keep"), ("sync_cursor", "1234")],
                             clone.execute("SELECT * FROM SyncMetadata ORDER BY key").fetchall())
        self.assertEqual(b"saved-audio", (self.destination / "audio" / "old.opus").read_bytes())
        self.assertEqual(1, len(report["copied_audio"]))
        self.assertEqual(hashlib.sha256(b"saved-audio").hexdigest(), report["copied_audio"][0]["sha256"])
        self.assertNotIn("PRIVATE-TOKEN", (self.destination / "recovery-report.json").read_text())

    def test_missing_external_partial_and_unreferenced_audio_are_preserved_at_source(self):
        outside = self.root / "private.mp3"
        outside.write_bytes(b"outside")
        partial = self.source / "audio" / "incomplete.part"
        partial.write_bytes(b"partial")
        orphan = self.source / "audio" / "unlinked.mp3"
        orphan.write_bytes(b"orphan")
        self.content("external", audio=outside)
        self.content("partial", audio=partial)
        self.content("missing", status="COMPLETED", audio=self.source / "audio" / "missing.mp3")
        self.content("completed-without-path", status="COMPLETED")
        report = recovery.clone_profile(self.source, self.destination)
        self.assertEqual([], report["copied_audio"])
        self.assertEqual({"ERROR"}, {item["status"] for item in report["content"]})
        self.assertEqual(b"outside", outside.read_bytes())
        self.assertEqual(b"partial", partial.read_bytes())
        self.assertEqual(b"orphan", orphan.read_bytes())
        self.assertFalse((self.destination / "audio" / orphan.name).exists())

    def test_consistent_snapshot_works_while_original_wal_writer_is_active(self):
        self.content()
        writer = sqlite3.connect(self.database)
        self.addCleanup(writer.close)
        writer.execute("PRAGMA journal_mode=WAL")
        writer.execute("UPDATE Content SET title='Uncommitted' WHERE id='video'")
        recovery.clone_profile(self.source, self.destination)
        with database(self.destination / "langkraft.db") as clone:
            self.assertEqual("Title", clone.execute("SELECT title FROM Content").fetchone()[0])
        self.assertEqual("Uncommitted", writer.execute("SELECT title FROM Content").fetchone()[0])
        writer.rollback()

    def test_rejects_existing_overlapping_and_future_profiles(self):
        self.content()
        self.destination.mkdir()
        sentinel = self.destination / "keep.txt"
        sentinel.write_text("keep")
        for destination in [self.destination, self.source / "nested", self.root]:
            with self.assertRaises(recovery.RecoveryError):
                recovery.clone_profile(self.source, destination)
        self.assertEqual("keep", sentinel.read_text())
        with database(self.database) as connection:
            connection.execute("PRAGMA user_version=4")
        with self.assertRaises(recovery.RecoveryError):
            recovery.clone_profile(self.source, self.root / "future")
        self.assertFalse((self.root / "future").exists())

    def test_older_schema_without_status_is_copied_without_schema_migration(self):
        with database(self.database) as connection:
            connection.execute("ALTER TABLE Content DROP COLUMN downloadStatus")
            connection.execute("PRAGMA user_version=1")
            connection.execute("INSERT INTO Content VALUES ('old','Old',NULL)")
        recovery.clone_profile(self.source, self.destination)
        with database(self.destination / "langkraft.db") as clone:
            self.assertEqual(1, clone.execute("PRAGMA user_version").fetchone()[0])
            self.assertNotIn("downloadStatus", recovery._columns(clone, "Content"))

    def test_source_change_during_audio_copy_is_reported_and_incomplete_output_is_removed(self):
        audio = self.source / "audio" / "changing.mp3"
        audio.write_bytes(b"audio")
        self.content(audio=audio)
        original = recovery._fingerprint
        count = 0

        def changed(info):
            nonlocal count
            count += 1
            fingerprint = original(info)
            return fingerprint if count == 1 else fingerprint[:-1] + (fingerprint[-1] + 1,)

        with patch.object(recovery, "_fingerprint", side_effect=changed):
            report = recovery.clone_profile(self.source, self.destination)
        self.assertEqual("audio_changed_during_copy", report["content"][0]["audio"])
        self.assertFalse((self.destination / "audio" / "changing.mp3").exists())
        self.assertEqual(b"audio", audio.read_bytes())

    def test_failed_copy_does_not_publish_profile_or_change_original(self):
        audio = self.source / "audio" / "audio.mp3"
        audio.write_bytes(b"audio")
        self.content(audio=audio)
        with patch.object(recovery, "_copy_audio", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(recovery.RecoveryError, "not published"):
                recovery.clone_profile(self.source, self.destination)
        self.assertFalse(self.destination.exists())
        self.assertEqual(1, len(list(self.root.glob("recovered.preparing-*"))))
        with database(self.database) as connection:
            self.assertEqual("DOWNLOADING", connection.execute("SELECT downloadStatus FROM Content").fetchone()[0])
        self.assertEqual(b"audio", audio.read_bytes())

    def test_schema_is_revalidated_after_snapshot_if_source_migrates_concurrently(self):
        self.content()
        create_preparing = tempfile.mkdtemp

        def migrate(*args, **kwargs):
            with database(self.database) as connection:
                connection.execute("PRAGMA user_version=4")
            return create_preparing(*args, **kwargs)

        with patch.object(recovery.tempfile, "mkdtemp", side_effect=migrate):
            with self.assertRaisesRegex(recovery.RecoveryError, "newer client schema"):
                recovery.clone_profile(self.source, self.destination)
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
