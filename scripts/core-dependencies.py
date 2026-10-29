"""Download and extract a core's pinned source dependencies."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path

manifest, source, cache = map(Path, sys.argv[1:])
for dependency in json.loads(manifest.read_text()).get("dependencies", []):
    archive = cache / dependency["url"].rsplit("/", 1)[-1]
    if not archive.is_file():
        temporary = archive.with_suffix(archive.suffix + ".part")
        subprocess.run(
            ["curl", "-fL", "-o", str(temporary), dependency["url"]], check=True
        )
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != dependency["sha256"]:
        raise SystemExit(f"Source dependency hash mismatch: {archive}")
    destination = source / dependency["path"]
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["tar", "-xf", str(archive), "-C", str(destination), "--strip-components", "1"],
        check=True,
    )
