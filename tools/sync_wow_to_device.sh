#!/usr/bin/env bash
set -euo pipefail

SRC_ROOT="${WOW_SRC:-/Applications/World of Warcraft}"
DST_ROOT="${WOW_DST:-/storage/emulated/0/WoW Forever}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v adb >/dev/null || export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"
adb get-state >/dev/null

list_local() {
    (cd "$SRC_ROOT/Data" && find . -type f ! -name shmem -exec stat -f '%z %N' {} + | sed 's| \./| |' | LC_ALL=C sort)
}

list_device() {
    adb shell "cd '$DST_ROOT/Data' 2>/dev/null && find . -type f ! -name shmem -exec stat -c '%s %n' {} +" | sed 's| \./| |' | LC_ALL=C sort
}

names() {
    cut -d' ' -f2- "$1" | LC_ALL=C sort
}

list_local > "$WORK/local.txt"
list_device > "$WORK/device.txt"

LC_ALL=C comm -23 "$WORK/local.txt" "$WORK/device.txt" | cut -d' ' -f2- > "$WORK/push.txt"
LC_ALL=C comm -13 <(names "$WORK/local.txt") <(names "$WORK/device.txt") > "$WORK/delete.txt"

echo "Deleting $(wc -l < "$WORK/delete.txt" | tr -d ' ') stale files, pushing $(wc -l < "$WORK/push.txt" | tr -d ' ') changed files"

while IFS= read -r f; do
    adb shell -n rm -f "'$DST_ROOT/Data/$f'"
done < "$WORK/delete.txt"

while IFS= read -r f; do
    adb shell -n mkdir -p "'$DST_ROOT/Data/$(dirname "$f")'"
    adb push "$SRC_ROOT/Data/$f" "$DST_ROOT/Data/$f" < /dev/null > /dev/null
    echo "pushed $f"
done < "$WORK/push.txt"

PRODUCT="$(python3 - "$SRC_ROOT/.build.info" <<'PY'
import sys
path = sys.argv[1]
lines = open(path).read().splitlines()
header = [h.split("!")[0] for h in lines[0].split("|")]
for line in lines[1:]:
    if not line.strip():
        continue
    row = dict(zip(header, line.split("|")))
    print(row.get("Product", ""))
    break
PY
)"
case "$PRODUCT" in
    wowf) FLAVOR_DIR="_forever_"; EXE_NAME="Wow-arm64.exe"; SCRIPT_EXE="Wow-ARM64.exe" ;;
    *) FLAVOR_DIR="_classic_beta_"; EXE_NAME="WowB-ARM64.exe"; SCRIPT_EXE="WowB-ARM64.exe" ;;
esac

local_build="$(cat "$SRC_ROOT/.build.info")"
device_build="$(adb shell cat "'$DST_ROOT/.build.info'" 2>/dev/null | tr -d '\r' || true)"
adb push "$SRC_ROOT/.build.info" "$DST_ROOT/.build.info" > /dev/null
adb shell "mkdir -p '$DST_ROOT/$FLAVOR_DIR'" >/dev/null
adb push "$SRC_ROOT/$FLAVOR_DIR/.flavor.info" "$DST_ROOT/$FLAVOR_DIR/.flavor.info" > /dev/null

if [ "$local_build" != "$device_build" ] || [ "${FORCE_EXE:-0}" = "1" ]; then
    WOW_DIR="$SRC_ROOT" WOW_PRODUCT="$PRODUCT" python3 "$REPO_DIR/tools/download_wow_arm64.py" "$WORK/exe"
    SRC_EXE="$SCRIPT_EXE"
    [ -f "$WORK/exe/Wow-ARM64.exe" ] && SRC_EXE="Wow-ARM64.exe"
    adb push "$WORK/exe/$SRC_EXE" "$DST_ROOT/$FLAVOR_DIR/$EXE_NAME" > /dev/null
else
    echo "Build unchanged, keeping $EXE_NAME"
fi

list_device > "$WORK/device.txt"
if cmp -s "$WORK/local.txt" "$WORK/device.txt"; then
    echo "Device Data matches $SRC_ROOT/Data"
else
    echo "Device Data still differs from $SRC_ROOT/Data" >&2
    exit 1
fi
