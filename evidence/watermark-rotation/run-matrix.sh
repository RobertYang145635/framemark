#!/usr/bin/env bash
# Capture the stage + the engine's exported JPEG at several watermark rotations.
# Usage: run-matrix.sh <tag-prefix> <angle>...
# Rotation is written into project.json (the UI slider only spans +-45) and the
# app is relaunched so the stage renders that exact spec.
set -u
export PATH='/c/Windows/System32:/c/Windows:/d/dev/android-sdk/platform-tools:'$PATH
export MSYS_NO_PATHCONV=1
PKG=com.framemark.native.dev
DEV=8267fe3e
cd "$(cd "$(dirname "$0")" && pwd)" || exit 1
TAG="$1"; shift

for R in "$@"; do
  python - "$R" <<'PY'
import json, sys
d = json.load(open('project-magenta.json', encoding='utf-8'))
r = float(sys.argv[1])
for k, v in d['designs'].items():
    if k == d['activePhotoId'] and v.get('overlay'):
        v['overlay']['rotation'] = r
json.dump(d, open('project-angle.json', 'w', encoding='utf-8'),
          ensure_ascii=False, separators=(',', ':'))
print('angle written', r)
PY
  adb -s $DEV shell am force-stop $PKG
  adb -s $DEV shell "run-as $PKG sh -c 'cat > files/project.json'" < project-angle.json
  adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 7
  adb -s $DEV exec-out screencap -p > "$TAG-stage-$R.png"
  adb -s $DEV shell input tap 1314 251
  sleep 2
  adb -s $DEV shell input tap 1047 2958
  sleep 12
  adb -s $DEV exec-out screencap -p > "$TAG-export-sheet-$R.png"
  NEW=$(adb -s $DEV shell 'ls -t /sdcard/Pictures/FrameMark/*.jpg 2>/dev/null | head -1' | tr -d '\r')
  adb -s $DEV exec-out cat "$NEW" > "$TAG-export-$R.jpg"
  adb -s $DEV shell input tap 1045 2958
  sleep 1.5
  echo "angle=$R export=$(basename "$NEW") bytes=$(stat -c %s "$TAG-export-$R.jpg")"
done
