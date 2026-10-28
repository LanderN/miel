#!/usr/bin/env bash
# Usage: scripts/release-notes.sh <tag>
#   Prints the release notes for <tag>: the feat, fix and perf commit subjects since the previous
#   vMAJOR.MINOR.PATCH tag, grouped by type.
set -euo pipefail
tag=$1
previous=$(git describe --tags --abbrev=0 --match 'v*.*.*' "$tag^" 2>/dev/null || true)
range=${previous:+$previous..}$tag

section() {
    local lines
    lines=$(git log --format=%s "$range" | sed -nE "s/^$2(\([^)]*\))?!?: (.*)/- \2/p")
    if [ -n "$lines" ]; then printf '## %s\n\n%s\n\n' "$1" "$lines"; fi
}

section Features feat
section Fixes fix
section Performance perf
echo 'Download miel-arm64-v8a.apk to install. Corresponding source, core dependencies, build scripts and patches are in miel-source.tar.gz. Third-party components retain their individual licenses. Checksums are in SHA256SUMS.'
