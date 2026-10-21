"""Create a committed-source ZIP for review, excluding tests and local artifacts."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def include(name: str) -> bool:
    parts = PurePosixPath(name).parts
    excluded = {
        "test", "tests", "commontest", "desktoptest", "jstest", "androidtest",
        "androidunittest", "androidinstrumentedtest", "karma.config.d", "archive",
        "build", "node_modules", ".gradle", ".git", ".idea", ".codex", ".agents",
        "web-storage-tests",
    }
    return not any(part.lower() in excluded for part in parts) and not any(
        part == ".env" or part.startswith(".env.") and part not in {".env.example", ".env.sample"}
        for part in parts
    )


def main() -> None:
    revision = git("rev-parse", "HEAD").decode().strip()
    output = ROOT / "build" / "review" / f"langkraft-sources-{revision[:8]}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    entries = []
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for raw in git("ls-tree", "-rz", "--full-tree", revision).split(b"\0"):
            if not raw:
                continue
            metadata, raw_name = raw.split(b"\t", 1)
            name = raw_name.decode("utf-8")
            if not include(name):
                continue
            mode, kind, object_id = metadata.split()
            if kind != b"blob":
                raise RuntimeError(f"Unsupported source entry: {name}")
            data = git("cat-file", "blob", object_id.decode("ascii"))
            archive.writestr("langkraft/" + name, data)
            entries.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        manifest = {
            "source_commit": revision,
            "tests_excluded": True,
            "excluded": "Tests, fixtures, browser test tooling, archived references, local configuration, build output and downloaded media",
            "review_instructions": "docs/AI_CODE_REVIEW.md",
            "files": entries,
        }
        archive.writestr("langkraft/ARCHIVE_MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("ZIP integrity check failed")
        for entry in entries:
            actual = hashlib.sha256(archive.read("langkraft/" + entry["path"])).hexdigest()
            if actual != entry["sha256"]:
                raise RuntimeError(f"Manifest mismatch: {entry['path']}")
    print(output)
    print(f"{len(entries)} files; ZIP and manifest verified; SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
