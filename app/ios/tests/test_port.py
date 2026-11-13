import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load_script("prepare_release", "prepare-apple-release.py")
checker = load_script("port_checker", "check-nuvx-port.py")


class PortIdentityTests(unittest.TestCase):
    def test_all_offline_source_checks(self):
        _, failures = checker.check()
        self.assertEqual(failures, [])

    def test_android_launcher_icon_choices_exist_on_ios(self):
        project = (ROOT / "mobile/project.yml").read_text(encoding="utf-8")
        self.assertIn("ASSETCATALOG_COMPILER_ALTERNATE_APPICON_NAMES: AppIconMonochrome", project)
        assets = ROOT.parent.parent / "core/apple/resources/Assets.xcassets"
        self.assertTrue((assets / "AppIcon.appiconset/AppIcon.png").is_file())
        self.assertTrue((assets / "AppIconMonochrome.appiconset/AppIconMonochrome.png").is_file())
        self.assertFalse((ROOT.parent.parent / "core/apple/src/UI/Settings/LicenseAcknowledgementsView.swift").exists())

    def test_ios_remains_native_and_macos_uses_the_rust_desktop(self):
        ios = (ROOT / "mobile/project.yml").read_text(encoding="utf-8")
        desktop = (ROOT.parent / "desktop/Cargo.toml").read_text(encoding="utf-8")
        desktop_main = (ROOT.parent / "desktop/src/main.rs").read_text(encoding="utf-8")
        desktop_renderer = (ROOT.parent / "desktop/src/renderer.rs").read_text(encoding="utf-8")
        self.assertIn("supportedDestinations: [iOS]", ios)
        self.assertNotIn("supportedDestinations: [iOS, macOS]", ios)
        self.assertIn("../../../core/apple/src", ios)
        self.assertIn("Generated/**", ios)
        self.assertEqual(ios.count("Sources/Widget"), 1)
        self.assertIn('cfg(target_os = "macos")', desktop)
        self.assertIn('features = ["apple-native"]', desktop)
        self.assertIn('target_os = "macos"', desktop_main)
        self.assertIn("wgpu::Backends::METAL", desktop_renderer)

    def test_release_accepts_new_app_identity(self):
        self.assertEqual(release.identity(self.plist()), ("0.8.0", 1))

    def test_ipa_packaging_matches_the_xcode_scheme_and_lowercase_bundle(self):
        script = (ROOT / "scripts/package-ios-ipa.sh").read_text(encoding="utf-8")
        self.assertIn("-scheme Nuvx", script)
        self.assertIn("Payload/nuvx.app/Info.plist", script)
        self.assertIn(r"^Payload/nuvx\.app/PlugIns/NuvxWidget\.appex", script)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", script)

    def test_apple_workflow_builds_rust_macos_and_unsigned_ios_without_signing_secrets(self):
        workflow = (ROOT.parents[1] / ".github/workflows/apple-builds.yml").read_text(encoding="utf-8")
        self.assertIn("bash scripts/package-ios-ipa.sh", workflow)
        self.assertIn('cargo test --locked --target "$ARCH-apple-darwin"', workflow)
        self.assertIn('sh macos/bundle.sh "$binary"', workflow)
        self.assertIn("macos-15-intel", workflow)
        self.assertIn('git rev-parse "$RELEASE_TAG^{commit}"', workflow)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("pull_request_target", workflow)

    @staticmethod
    def plist(**overrides):
        return {
            "CFBundleIdentifier": "com.bxane.nuvx",
            "CFBundleShortVersionString": "0.8.0",
            "CFBundleVersion": "1",
            "NuvxRevision": "123456789abc",
            **overrides,
        }

    def test_release_rejects_other_bundle(self):
        with self.assertRaises(ValueError):
            release.identity(self.plist(CFBundleIdentifier="org.example.other"))

    def test_paired_manifest_has_nuvx_packages_and_checksums(self):
        with tempfile.TemporaryDirectory(prefix="nuvx-tests-") as folder:
            root = Path(folder)
            ipa, dmg = root / "nuvx-0.8.0-build1-ios-unsigned.ipa", root / "nuvx-0.8.0-build1-mac-arm64.dmg"
            ipa.write_bytes(b"test-ipa")
            dmg.write_bytes(b"test-dmg")
            manifest = release.prepare(ipa, dmg, self.plist(), self.plist(), root / "output")
            self.assertEqual(manifest["revision"], "123456789abc")
            self.assertEqual({a["platform"] for a in manifest["artifacts"]}, {"ios", "macos"})
            self.assertTrue(all(len(a["sha256"]) == 64 for a in manifest["artifacts"]))
            self.assertIn("nuvx", (root / "output/INSTALL.md").read_text())

    def test_mismatched_builds_write_nothing(self):
        with tempfile.TemporaryDirectory(prefix="nuvx-tests-") as folder:
            root = Path(folder)
            with self.assertRaises(ValueError):
                release.prepare(root / "app.ipa", root / "app.dmg", self.plist(),
                                self.plist(CFBundleVersion="2"), root / "output")
            self.assertFalse((root / "output").exists())


if __name__ == "__main__":
    unittest.main()
