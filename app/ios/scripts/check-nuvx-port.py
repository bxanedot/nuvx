#!/usr/bin/env python3
"""Offline source/identity/resource checks. This is NOT an Apple compile."""
from pathlib import Path
import hashlib
import json
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
APPLE_SHARED = ROOT.parent.parent / "core/apple"
APPLE_RESOURCES = APPLE_SHARED / "resources/Resources"
IGNORED = {".git", ".gradle", ".kotlin", "build", "target", "__pycache__"}
LEGAL_FILES = {
    "THIRD_PARTY_LICENSES.md",
}
MODELS = {
    "beat_this.onnx": (23478460, "9f10e5efa37d3e120da2121141825fe54b23b487b32dd25a54f89039fd8f85cb"),
    "beat_this_final0_coreml.aar": (18826124, "cd00f20bd602f4d1532f0b683fe418f278affead3494f954032039d902b5b88f"),
    "vocals_umxhq_int8.onnx": (23209009, "7eba6bebce0cc2e2d3b5c0e490a8074e66ce27f64170c6802b245740dcaf21ef"),
}


def source_files(root=ROOT):
    for source_root in (root, APPLE_SHARED):
        for path in source_root.rglob("*"):
            relative = path.relative_to(source_root)
            if path.is_file() and not IGNORED.intersection(relative.parts):
                yield path


def check(root=ROOT):
    failures = []
    attribution = (root / "THIRD_PARTY_LICENSES.md").read_text(encoding="utf-8")
    legacy = [line.partition(": ")[2].lower() for line in attribution.splitlines()
              if line.startswith("Legacy ") and ": " in line]
    files = list(source_files(root))
    assets = APPLE_SHARED / "resources/Assets.xcassets"
    symbol_names = {re.sub("[^a-z0-9]", "", p.stem.lower())
                    for p in assets.iterdir() if p.suffix in (".imageset", ".colorset")}

    for path in files:
        relative = (path.relative_to(root).as_posix() if path.is_relative_to(root)
                    else "core/apple/" + path.relative_to(APPLE_SHARED).as_posix())
        if any(word in relative.lower() for word in legacy):
            failures.append(f"Legacy product/author in path: {relative}")
        try:
            data = path.read_bytes()
            text = data.decode("utf-8")
            if "\0" in text:
                continue
        except UnicodeDecodeError:
            continue
        legal = (relative in LEGAL_FILES or "AudioLicenses" in path.parts
                 or path.name in ("LICENSE", "COPYING", "COPYING.LGPL")
                 or path.name == "THIRD_PARTY_NOTICES.md")
        if not legal:
            for number, line in enumerate(text.splitlines(), 1):

                required_header = (relative.startswith("native-core/src/analyzer/")
                                   and number <= 15 and line.startswith("//!"))
                if not required_header and any(word in line.lower() for word in legacy):
                    failures.append(f"Legacy branding outside legal notices: {relative}:{number}")
        if path.suffix == ".json" or path.suffix == ".xcstrings":
            try:
                json.loads(text)
            except ValueError as error:
                failures.append(f"Invalid JSON: {relative}: {error}")
        if path.suffix == ".swift":
            for name in re.findall(r"(?<![\w])\.(nuvx[A-Z]\w*)", text):
                if name.lower() not in symbol_names:
                    failures.append(f"Missing typed asset {name}: {relative}")

    for manifest in assets.rglob("Contents.json"):
        contents = json.loads(manifest.read_text(encoding="utf-8"))
        for image in contents.get("images", []):
            if "filename" in image and not (manifest.parent / image["filename"]).is_file():
                failures.append(f"Missing image: {manifest.parent.name}/{image['filename']}")

    icon = assets / "AppIcon.appiconset/AppIcon.png"
    header = icon.read_bytes()[:33]
    if header[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", header[16:24]) != (1024, 1024):
        failures.append("AppIcon.png must be a 1024x1024 PNG")
    if header[25] != 2:
        failures.append("Primary app icon must be opaque RGB, not RGBA")
    alternate_icon = assets / "AppIconMonochrome.appiconset/AppIconMonochrome.png"
    if not alternate_icon.is_file():
        failures.append("Missing monochrome app icon")
    else:
        alternate_header = alternate_icon.read_bytes()[:33]
        if (alternate_header[:8] != b"\x89PNG\r\n\x1a\n"
                or struct.unpack(">II", alternate_header[16:24]) != (1024, 1024)):
            failures.append("Monochrome app icon must be a 1024x1024 PNG")
        if alternate_header[25] != 2:
            failures.append("Monochrome app icon must be opaque RGB, not RGBA")
    for name, (size, digest) in MODELS.items():
        path = APPLE_RESOURCES / "Models" / name
        if not path.is_file():
            failures.append(f"Missing model: {name}")
        elif path.stat().st_size != size or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            failures.append(f"Model checksum/size mismatch: {name}")
    spec = (root / "mobile/project.yml").read_text(encoding="utf-8")
    for required in [
        "PRODUCT_BUNDLE_IDENTIFIER: com.bxane.nuvx\n",
        "PRODUCT_BUNDLE_IDENTIFIER: com.bxane.nuvx.widget\n",
        "group.com.bxane.nuvx", "CFBundleDisplayName: nuvx\n",
        "CFBundleURLSchemes: [nuvx]", "SUEnableAutomaticChecks: false",
        'NUVX_UPDATE_FEED_URL: ""', 'NUVX_UPDATE_PUBLIC_KEY: ""',
        "Frameworks/NuvxShared.xcframework", "Resources/Models",
        "ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES: AppIconMonochrome",
        "AppIconMonochrome.icon",
    ]:
        if required not in spec:
            failures.append(f"Missing/incorrect Xcode setting: {required.strip()}")
    for required in [
        "supportedDestinations: [iOS]", "path: ../../../core/apple/src",
        "Generated/**",
    ]:
        if required not in spec:
            failures.append(f"Missing/incorrect iOS project setting: {required}")
    desktop_manifest = (root.parent / "desktop/Cargo.toml").read_text(encoding="utf-8")
    desktop_main = (root.parent / "desktop/src/main.rs").read_text(encoding="utf-8")
    desktop_renderer = (root.parent / "desktop/src/renderer.rs").read_text(encoding="utf-8")
    for required in [
        "[target.'cfg(target_os = \"macos\")'.dependencies]",
        'keyring = { version = "3.6.3", features = ["apple-native"] }',
        '"metal"',
    ]:
        if required not in desktop_manifest:
            failures.append(f"Missing macOS Rust desktop setting: {required}")
    if 'target_os = "macos"' not in desktop_main:
        failures.append("Rust desktop app does not allow the macOS target")
    if "wgpu::Backends::METAL" not in desktop_renderer:
        failures.append("Rust desktop renderer is missing the macOS Metal backend")
    for path in ["build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat",
                 "gradle/wrapper/gradle-wrapper.jar", "LICENSE",
                  "../../core/apple/resources/Resources/AudioLicenses/ThirdPartyNotices.txt"]:
        if not (root / path).is_file():
            failures.append(f"Missing build/license input: {path}")
    for manifest in (root / "shared/build.gradle.kts",):
        text = manifest.read_text(encoding="utf-8")
        if "https://nuvx-listen.bxane.workers.dev" not in text:
            failures.append("Official Listen Together default is missing")
    return files, failures


def main():
    files, failures = check()
    if failures:
        print("\n".join(f"FAIL: {failure}" for failure in failures))
        return 1
    print(f"PASS: {len(files)} source/resource files checked.")
    print("PASS: nuvx app/widget/group identity, legal-only historical attribution, JSON, asset references, opaque icons, model hashes and build inputs.")
    print("NOT VERIFIED: Swift type-checking/linking, Apple signing, screenshots, device playback.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
