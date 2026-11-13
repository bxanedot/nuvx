#!/usr/bin/env python3
"""Check that repository notices live in the root third-party directory."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NOTICE_ROOT = ROOT / "THIRD_PARTY_LICENSES"
NOTICE_NAME = re.compile(
    r"LICEN[CS]E(?:[.-].*)?|COPYING(?:[.-].*)?|COPYRIGHT|NOTICE(?:[.-].*)?|"
    r"THIRD_PARTY_(?:LICENSES|NOTICES)\.(?:md|txt)|OFL(?:[.-].*)?",
    re.I,
)
SOURCE_SUFFIXES = {
    ".rs", ".kt", ".kts", ".swift", ".c", ".cc", ".h", ".cpp", ".hpp",
    ".js", ".ts", ".tsx", ".java", ".in", ".sh", ".py", ".cmake", ".bat",
}
NOTICE_HEADER = re.compile(
    r"(?mi)^[ \t]*(?://[!/]*|/\*+|\*|#(?!\!)|@?rem\b)[^\r\n]*"
    r"(?:SPDX-License-Identifier|copyright|licen[cs]ed under|licen[cs]e for this file|"
    r"\b(?:AGPL|GPL|LGPL)[-v ]\d)"
)


def check():
    failures = []
    files = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    ).decode().rstrip("\0").split("\0")
    for name in sorted(set(files)):
        path = ROOT / name
        if not path.is_file() or path.is_relative_to(NOTICE_ROOT):
            continue
        if NOTICE_NAME.fullmatch(path.name):
            failures.append(f"Scattered notice file: {name}")
        if path.suffix.lower() not in SOURCE_SUFFIXES and path.name != "CMakeLists.txt" and path.suffix:
            continue
        try:
            text = path.read_bytes().decode("utf-8")
        except UnicodeDecodeError:
            continue
        for match in NOTICE_HEADER.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            failures.append(f"Source notice outside the central directory: {name}:{line}")
    manifest = json.loads((NOTICE_ROOT / "relocation.json").read_text(encoding="utf-8"))
    for original, entry in manifest["files"].items():
        target = ROOT / entry["path"]
        if not target.is_relative_to(NOTICE_ROOT) or not target.is_file():
            failures.append(f"Missing central notice for {original}")
        elif hashlib.sha256(target.read_bytes()).hexdigest() != entry["sha256"]:
            failures.append(f"Central notice checksum changed: {entry['path']}")
    for path in (NOTICE_ROOT / "SOURCE_NOTICES.md", NOTICE_ROOT / "README.md"):
        if not path.is_file():
            failures.append(f"Missing central notice index: {path.name}")
    return failures


if __name__ == "__main__":
    errors = check()
    if errors:
        print("\n".join(errors))
        raise SystemExit(1)
    print("PASS: standalone license texts and source-header notices are centralized in THIRD_PARTY_LICENSES/.")
