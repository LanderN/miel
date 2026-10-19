#!/usr/bin/env bash
# Usage: scripts/test-core-host.sh [software|hardware|probe|invalid|stage-invalid] [romUri filename]
#   Runs the core host instrumentation on $ANDROID_SERIAL (default emulator-5554); default mode is hardware.
#   stage-invalid needs an encoded synthetic-fixture content:// URI; filename defaults to synthetic.3ds.
set -euo pipefail
cd "$(dirname "$0")/.."
die() {
    echo "$*" >&2
    exit 1
}
mode=${1:-hardware}
rom_uri=${2:-}
filename=${3:-synthetic.3ds}
serial=${ANDROID_SERIAL:-emulator-5554}
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with platform-tools}"
adb=$ANDROID_HOME/platform-tools/adb

args=(-s "$serial" shell am instrument -w)
case $mode in
    software) ;;
    hardware) args+=(-e renderer hardware) ;;
    probe | invalid) args+=(-e mode "$mode") ;;
    stage-invalid)
        [[ $rom_uri =~ ^content://[A-Za-z0-9%_./:-]+$ && $filename =~ ^[a-zA-Z0-9_.-]+$ ]] ||
            die "Provide an encoded synthetic-fixture content URI and a simple filename"
        args+=(-e mode "$mode" -e romUri "$rom_uri" -e filename "$filename")
        ;;
    *)
        echo "Unknown mode: $mode" >&2
        exit 1
        ;;
esac
args+=(io.github.landern.miel.test/io.github.landern.miel.CoreHostInstrumentation)

status=0
result=$("$adb" "${args[@]}" 2>&1) || status=$?
printf '%s\n' "$result"
if [ "$status" -ne 0 ] || ! grep -q '^PASS:' <<<"$result" || grep -q '^FAIL:' <<<"$result"; then
    echo "Core host check failed: $mode" >&2
    exit 1
fi
