"""Collect core notices and optionally package release source archives."""

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import tarfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sources", type=Path, help="Output corresponding-source archive")
parser.add_argument(
    "cores", nargs="+", type=Path, help="Core source directories used by the build"
)
args = parser.parse_args()
args.cores = [p.resolve().relative_to(Path.cwd()) for p in args.cores]
notices = Path("android/app/build/generated/core-assets/notices")
notices.mkdir(parents=True, exist_ok=True)
for previous in notices.glob("*-dependencies.txt"):
    previous.unlink()
license_name = re.compile(
    r"(?:.*[-_])?(?:licen[cs]e|copying|copyright|notice|unlicense)(?:[._-].*)?",
    re.IGNORECASE,
)
dependencies = {}

for core in args.cores:
    name = core.parent.name
    roots = [core, *sorted((core / "build/_deps").glob("*-src"))]
    dependencies[name] = roots[1:]
    if name == "melonds-ds" and not dependencies[name]:
        raise SystemExit(f"Fetched dependency sources are missing from {core}")
    sections = []
    for root in roots:
        for directory, dirs, files in os.walk(root):
            dirs[:] = sorted(
                d
                for d in dirs
                if d not in {".git", "build", "CMakeFiles", "__pycache__"}
            )
            for filename in sorted(files):
                if license_name.fullmatch(filename):
                    path = Path(directory) / filename
                    sections.append(
                        f"{path.relative_to(core)}\n\n{path.read_text(errors='replace')}"
                    )
    generated = core / "build/melondsds-LICENSE.txt"
    if generated.is_file():
        sections.append(generated.read_text())
    if not sections:
        raise SystemExit(f"No licenses found in {core}")
    (notices / f"{name}-dependencies.txt").write_text("\n\n".join(sections) + "\n")
    print(f"{name}: {len(sections)} license sections")

if args.sources:
    args.sources.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.sources, "w:gz") as archive:
        files = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
        for filename in filter(None, files):
            archive.add(filename, arcname=f"miel/{filename}", recursive=False)
        for core in args.cores:
            name = core.parent.name
            manifest = json.loads(
                (Path("third_party") / name / "manifest.json").read_text()
            )
            sources = [
                {"url": manifest["source_url"], "sha256": manifest["source_sha256"]},
                *manifest.get("dependencies", []),
            ]
            for source in sources:
                upstream = core.parent / source["url"].rsplit("/", 1)[-1]
                if (
                    hashlib.sha256(upstream.read_bytes()).hexdigest()
                    != source["sha256"]
                ):
                    raise SystemExit(f"Source archive hash mismatch: {upstream}")
                archive.add(upstream, arcname=f"miel/{upstream}")
            for dependency in dependencies[name]:
                archive.add(
                    dependency,
                    arcname=f"miel/dependencies/{name}/{dependency.name}",
                    filter=lambda entry: (
                        None if ".git" in Path(entry.name).parts else entry
                    ),
                )
        instructions = (
            b"miel corresponding source\n\n"
            b"The repository files include build scripts, core manifests and patches.\n"
            b"Core upstream archives are in .tools/<core>/, with SHA-256 values in\n"
            b"third_party/<core>/manifest.json. scripts/build.sh extracts and patches them.\n"
            b"Fetched dependency sources are in dependencies/<core>/. Their upstream\n"
            b"CMake files specify the dependency repositories and revisions.\n"
            b"Install the JDK, Android SDK, NDK and CMake versions selected by the build\n"
            b"scripts and manifests. Run scripts/build.sh android arm64-v8a for a debug\n"
            b"APK with your own debug key; release signing uses your own signing key.\n"
            b"Third-party code and assets retain their individual license terms.\n"
        )
        info = tarfile.TarInfo("miel/SOURCE.txt")
        info.size = len(instructions)
        archive.addfile(info, io.BytesIO(instructions))
    print(args.sources)
