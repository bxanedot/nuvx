#!/bin/bash

set -euo pipefail
app="${1:?Pass the path to a Debug macOS nuvx.app}"
validation_dir="$(mktemp -d)"
trap 'cp "$validation_dir/runtime.log" /tmp/nuvx-queue-runtime-last.log 2>/dev/null || true; rm -rf "$validation_dir"' EXIT
validation_app="$validation_dir/NuvxQueueValidation.app"
ditto "$app" "$validation_app"
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier com.bxane.nuvx.queuevalidation.run-$RANDOM-$RANDOM" "$validation_app/Contents/Info.plist"
codesign --force --deep --sign - "$validation_app" > "$validation_dir/sign.log" 2>&1
rm -f /tmp/nuvx-queue-runtime.json
"$validation_app/Contents/MacOS/Nuvx" --verify-queue > "$validation_dir/runtime.log" 2>&1
python3 - <<'PY'
import json
with open('/tmp/nuvx-queue-runtime.json') as f:
    report = json.load(f)
failed = [name for name, passed in report['checks'].items() if not passed]
assert report['passed'], f'Queue runtime checks failed: {failed}'
print(f"PASS {len(report['checks'])} Mac controller/runtime checks")
PY
