"""Audit a client profile or recover legacy downloads into a NEW isolated copy."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import tempfile
import time


class RecoveryError(Exception):
    pass


class UnavailableAudio(RecoveryError):
    pass


def _readonly(profile: Path) -> sqlite3.Connection:
    database = (profile / "langkraft.db").resolve(strict=True)
    if database.parent != profile or not database.is_file():
        raise RecoveryError("Expected a regular client langkraft.db inside the source profile")
    connection = sqlite3.connect(database.as_uri() + "?mode=ro", uri=True, timeout=5)
    try:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON")
        return connection
    except BaseException:
        connection.close()
        raise


def _columns(connection: sqlite3.Connection, table: str) -> set[str]:
    # Table names are internal constants, never CLI input.
    return {row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')}


def _rows(connection: sqlite3.Connection) -> list[sqlite3.Row]:
    columns = _columns(connection, "Content")
    if not {"id", "localAudioPath"} <= columns:
        raise RecoveryError("Unsupported client Content schema")
    status = '"downloadStatus"' if "downloadStatus" in columns else "'IDLE'"
    rows = list(connection.execute(f'SELECT id, localAudioPath, {status} AS downloadStatus FROM Content ORDER BY id'))
    if any(not isinstance(row["id"], str) or not row["id"] or
           row["localAudioPath"] is not None and not isinstance(row["localAudioPath"], str) or
           not isinstance(row["downloadStatus"], str) for row in rows):
        raise RecoveryError("Malformed client Content values")
    return rows


def _audio_file(profile: Path, location: str) -> Path:
    root = profile / "audio"
    path = Path(location)
    if not path.is_absolute():
        path = profile / path
    path = path.resolve()
    if not path.is_relative_to(root) or root.is_symlink():
        raise UnavailableAudio("outside_managed_audio")
    if path.name.endswith((".part", ".pending", ".pending.tmp")):
        raise UnavailableAudio("unfinished_audio")
    return path


def audit_profile(source: Path) -> dict:
    profile = source.resolve(strict=True)
    connection = _readonly(profile)
    try:
        connection.execute("BEGIN")
        rows = _rows(connection)
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        markers = {}
        if {"key", "value"} <= _columns(connection, "SyncMetadata"):
            markers = dict(connection.execute("SELECT key,value FROM SyncMetadata WHERE instr(key,'audio_download_lock:')=1"))
        downloads = [{"id": row["id"], "status": row["downloadStatus"], "local_audio": row["localAudioPath"],
                      "protocol": markers.get("audio_download_lock:" + row["id"]), "owner_state": "unknown"}
                     for row in rows if row["downloadStatus"] == "DOWNLOADING"]
    finally:
        connection.close()
    artifacts = []
    audio = profile / "audio"
    if audio.is_symlink():
        artifacts.append({"path": "audio", "kind": "symlink_not_followed"})
    elif audio.exists():
        for path in sorted(audio.iterdir()):
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode):
                kind = "non_regular_entry"
            elif path.name.endswith(".pending"):
                kind = "journal_not_interpreted"
            elif path.name.endswith(".part"):
                kind = "partial_owner_unknown"
            else:
                kind = "audio_history_unknown"
            artifacts.append({"path": str(path.relative_to(profile)), "kind": kind, "bytes": info.st_size})
    return {"mode": "audit", "source": str(profile), "schema_version": version,
            "content_count": len(rows), "stuck_downloads": downloads, "artifacts": artifacts,
            "policy": "No source changes. Missing ownership metadata never proves that a writer has stopped."}


def _fingerprint(info: os.stat_result) -> tuple:
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns


def _copy_audio(source: Path, target: Path, output_root: Path) -> dict:
    if not target.resolve().is_relative_to(output_root):
        raise RecoveryError("Audio output escaped the preparing profile")
    created = False
    try:
        stream = source.open("rb")
    except OSError as error:
        raise UnavailableAudio("unreadable_audio") from error
    try:
        with stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or not before.st_size:
                raise UnavailableAudio("empty_or_non_regular_audio")
            target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            count = 0
            output = target.open("xb")
            created = True
            with output:
                while True:
                    try:
                        chunk = stream.read(1024 * 1024)
                    except OSError as error:
                        raise UnavailableAudio("audio_read_failed") from error
                    if not chunk:
                        break
                    output.write(chunk)
                    digest.update(chunk)
                    count += len(chunk)
                output.flush()
                os.fsync(output.fileno())
            try:
                unchanged = _fingerprint(before) == _fingerprint(os.fstat(stream.fileno())) == _fingerprint(source.stat())
            except OSError as error:
                raise UnavailableAudio("audio_disappeared") from error
            if not unchanged or count != before.st_size or source.resolve() != source:
                raise UnavailableAudio("audio_changed_during_copy")
            return {"bytes": count, "sha256": digest.hexdigest()}
    except BaseException:
        if created:
            target.unlink(missing_ok=True)  # Only a newly-created file inside output_root.
        raise


def clone_profile(source: Path, destination: Path) -> dict:
    profile = source.resolve(strict=True)
    if destination.is_symlink():
        raise RecoveryError("Destination must be a new profile, not a symlink")
    target = destination.resolve()
    if target.exists() or target.is_relative_to(profile) or profile.is_relative_to(target):
        raise RecoveryError("Destination must be new and must not overlap the source profile")
    connection = _readonly(profile)
    preparing = None
    clone = None
    try:
        _rows(connection)  # Reject backend/unsupported databases before creating output.
        if connection.execute("PRAGMA user_version").fetchone()[0] > 3:
            raise RecoveryError("A newer client schema cannot be recovered by this tool")
        target.parent.mkdir(parents=True, exist_ok=True)
        preparing = Path(tempfile.mkdtemp(prefix=target.name + ".preparing-", dir=target.parent)).resolve()
        clone = sqlite3.connect(preparing / "langkraft.db")
        clone.row_factory = sqlite3.Row
        deadline = time.monotonic() + 30

        def progress(_status, _remaining, _total):
            if time.monotonic() > deadline:
                raise RecoveryError("SQLite snapshot timed out; retry when the source is quieter")

        connection.backup(clone, pages=128, progress=progress, sleep=0.05)
        connection.close()
        if clone.execute("PRAGMA user_version").fetchone()[0] > 3:
            raise RecoveryError("Snapshot contains a newer client schema")
        rows = _rows(clone)
        has_status = "downloadStatus" in _columns(clone, "Content")
        report = {"mode": "clone", "source": str(profile), "destination": str(target),
                  "source_unchanged": True, "auth_token_removed": False, "content": [], "copied_audio": []}
        copied = {}
        # All SQL changes are confined to the snapshot. Other tables (SRS, cues,
        # pending sync, source references, cursors) retain their snapshot values.
        with clone:
            if {"key", "value"} <= _columns(clone, "SyncMetadata"):
                clone.execute("DELETE FROM SyncMetadata WHERE key='auth_token' OR instr(key,'audio_download_lock:')=1")
                report["auth_token_removed"] = True
            for row in rows:
                old_location = row["localAudioPath"]
                new_location = None
                reason = "no_local_audio"
                if old_location:
                    try:
                        audio = _audio_file(profile, old_location)
                        relative = audio.relative_to(profile / "audio")
                        if audio not in copied:
                            details = _copy_audio(audio, preparing / "audio" / relative, preparing)
                            copied[audio] = str(target / "audio" / relative)
                            report["copied_audio"].append({"path": str(Path("audio") / relative), **details})
                        new_location = copied[audio]
                        reason = "copied"
                    except UnavailableAudio as error:
                        reason = str(error)
                status = row["downloadStatus"]
                if has_status and status == "DOWNLOADING":
                    status = "COMPLETED" if new_location else "ERROR"
                elif has_status and not new_location and (old_location or status == "COMPLETED"):
                    status = "ERROR"
                elif has_status and new_location and status == "IDLE":
                    status = "COMPLETED"
                if has_status:
                    clone.execute("UPDATE Content SET localAudioPath=?, downloadStatus=? WHERE id=?", (new_location, status, row["id"]))
                else:
                    clone.execute("UPDATE Content SET localAudioPath=? WHERE id=?", (new_location, row["id"]))
                report["content"].append({"id": row["id"], "previous_status": row["downloadStatus"],
                                          "status": status, "audio": reason})
        if clone.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RecoveryError("Snapshot integrity check failed")
        clone.close()
        clone = None
        (preparing / "recovery-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if preparing.parent != target.parent or target.exists() or target.is_symlink():
            raise RecoveryError("Destination changed while preparing the profile")
        preparing.rename(target)
        return report
    except BaseException as error:
        if preparing is not None:
            raise RecoveryError(f"Recovery was not published; preparing profile retained at {preparing}: {error}") from error
        raise
    finally:
        connection.close()
        if clone is not None:
            clone.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Desktop/TUI profile containing langkraft.db")
    parser.add_argument("--clone-to", type=Path, help="NEW isolated profile; without this option only a read-only audit is performed")
    args = parser.parse_args()
    try:
        report = clone_profile(args.source, args.clone_to) if args.clone_to else audit_profile(args.source)
    except (RecoveryError, OSError, sqlite3.Error) as error:
        parser.exit(1, f"Recovery failed: {error}\n")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
