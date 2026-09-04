#!/usr/bin/env bash
# Pull flagged panel-detection pages (good/ + bad/) from the phone to the PC.
#
# The app writes them under <base storage dir>/panel_flags/{good,bad}/ as
# <name>.jpg + <name>.json pairs (see PanelFlagExporter / StorageManager).
# This copies only those two extensions, is incremental (skips files already
# present locally with a matching byte size), and never deletes anything.
#
# Usage:
#   tools/pull-panel-flags.sh              # incremental pull to panel_flags_pulled/
#   tools/pull-panel-flags.sh --force      # re-pull every file
#   REMOTE_DIR=/sdcard/mihon/panel_flags tools/pull-panel-flags.sh
#   LOCAL_DIR=/c/tmp/flags tools/pull-panel-flags.sh
#
# Windows/Git Bash: MSYS_NO_PATHCONV is set below so adb's /sdcard paths
# aren't mangled into C:\... by the MSYS path translator.

set -euo pipefail
export MSYS_NO_PATHCONV=1

REMOTE_DIR="${REMOTE_DIR:-/sdcard/Mihon/panel_flags}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${LOCAL_DIR:-$REPO_ROOT/panel_flags_pulled/panel_flags}"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

command -v adb >/dev/null || { echo "adb not on PATH" >&2; exit 1; }

DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
[ -z "$DEVICES" ] && { echo "no adb device (need 'adb devices' to list one as 'device')" >&2; exit 1; }
COUNT="$(printf '%s\n' "$DEVICES" | wc -l)"
[ "$COUNT" -gt 1 ] && { echo "multiple devices attached; set ANDROID_SERIAL to pick one:" >&2; echo "$DEVICES" >&2; exit 1; }

adb shell "[ -d '$REMOTE_DIR' ]" || { echo "remote dir not found: $REMOTE_DIR" >&2; exit 1; }

echo "remote: $REMOTE_DIR"
echo "local:  $LOCAL_DIR"
echo

# remote listing: "<size>|<relative path>" for good/ + bad/, jpg + json only
REMOTE_LIST="$(adb shell "cd '$REMOTE_DIR' && for f in good/*.jpg good/*.json bad/*.jpg bad/*.json; do [ -e \"\$f\" ] && stat -c '%s|%n' \"\$f\"; done" | tr -d '\r')"

new=0 updated=0 skipped=0 total=0
mkdir -p "$LOCAL_DIR/good" "$LOCAL_DIR/bad"

while IFS='|' read -r size rel; do
    [ -z "${rel:-}" ] && continue
    total=$((total + 1))
    dest="$LOCAL_DIR/$rel"
    if [ "$FORCE" -eq 0 ] && [ -f "$dest" ]; then
        local_size="$(stat -c %s "$dest" 2>/dev/null || stat -f %z "$dest" 2>/dev/null || echo -1)"
        if [ "$local_size" = "$size" ]; then
            skipped=$((skipped + 1))
            continue
        fi
        updated=$((updated + 1))
    else
        [ -f "$dest" ] && updated=$((updated + 1)) || new=$((new + 1))
    fi
    mkdir -p "$(dirname "$dest")"
    # Pull with a relative local target from inside LOCAL_DIR: an absolute Windows
    # path like /c/Users/... isn't understood by adb.exe, and MSYS_NO_PATHCONV (set
    # for the remote /sdcard path) also blocks the Git Bash translation that would
    # otherwise fix it. A relative "good/x.jpg" has no drive/leading-slash so both
    # adb and MSYS leave it alone.
    if out="$( cd "$LOCAL_DIR" && adb pull -a "$REMOTE_DIR/$rel" "$rel" 2>&1 )"; then
        printf '  %s\n' "$rel"
    else
        printf '  FAILED %s\n%s\n' "$rel" "$out" >&2
    fi
done <<< "$REMOTE_LIST"

echo
echo "done: $total remote  |  $new new  $updated updated  $skipped unchanged"
echo "pairs local: good=$(( $(find "$LOCAL_DIR/good" -name '*.jpg' | wc -l) ))  bad=$(( $(find "$LOCAL_DIR/bad" -name '*.jpg' | wc -l) ))"
