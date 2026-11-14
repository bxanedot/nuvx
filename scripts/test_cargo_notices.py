import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("cargo_notices.py")
SPEC = importlib.util.spec_from_file_location("cargo_notices", MODULE_PATH)
cargo_notices = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cargo_notices)


class CargoNoticesTests(unittest.TestCase):
    def test_license_expression_returns_all_license_and_exception_ids(self):
        self.assertEqual(
            cargo_notices.license_ids("Apache-2.0 WITH LLVM-exception OR MIT/Apache-2.0"),
            {"Apache-2.0", "LLVM-exception", "MIT"},
        )

    def test_runtime_graph_skips_build_and_dev_dependencies(self):
        metadata = {
            "resolve": {
                "root": "root",
                "nodes": [
                    {"id": "root", "deps": [
                        {"pkg": "runtime", "dep_kinds": [{"kind": None, "target": None}]},
                        {"pkg": "build", "dep_kinds": [{"kind": "build", "target": None}]},
                        {"pkg": "dev", "dep_kinds": [{"kind": "dev", "target": None}]},
                    ]},
                    {"id": "runtime", "deps": [
                        {"pkg": "transitive", "dep_kinds": [{"kind": None, "target": None}]},
                    ]},
                    {"id": "build", "deps": []},
                    {"id": "dev", "deps": []},
                    {"id": "transitive", "deps": []},
                ],
            },
            "packages": [{"id": item} for item in ("root", "runtime", "build", "dev", "transitive")],
        }
        self.assertEqual(cargo_notices.runtime_dependency_ids(metadata), {"runtime", "transitive"})

    def test_render_includes_target_attribution_and_license_text(self):
        report = cargo_notices.render_notices(
            {"x86_64-unknown-linux-gnu": [{
                "name": "sample",
                "version": "1.0.0",
                "license": "MIT",
                "authors": ["Example Author"],
                "source": "registry+https://example.invalid/index",
                "repository": "https://example.invalid/sample",
            }]},
            {"MIT": "MIT license text"},
            {},
        )
        self.assertIn("x86_64-unknown-linux-gnu", report)
        self.assertIn("Example Author", report)
        self.assertIn("registry+https://example.invalid/index", report)
        self.assertIn("not asserted to be copyright holders", report)
        self.assertIn("https://example.invalid/sample", report)
        self.assertIn("MIT license text", report)

    def test_source_notices_includes_root_files_and_license_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "Cargo.toml").write_text("[package]")
            (root / "LICENSE-MIT").write_text("MIT notice")
            (root / "LICENSES").mkdir()
            (root / "LICENSES" / "Apache-2.0.txt").write_text("Apache notice")
            files = cargo_notices.source_notices({
                "manifest_path": str(root / "Cargo.toml"),
                "name": "sample",
                "version": "1.0.0",
            })
            self.assertEqual({path.name for path in files}, {"LICENSE-MIT", "Apache-2.0.txt"})

    def test_missing_license_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest = Path(temp) / "Cargo.toml"
            manifest.write_text("[package]")
            with self.assertRaisesRegex(ValueError, "missing Cargo license metadata"):
                cargo_notices.collect_notices({"target": [{
                    "manifest_path": str(manifest),
                    "name": "sample",
                    "version": "1.0.0",
                    "license": None,
                    "license_file": None,
                }]})


if __name__ == "__main__":
    unittest.main()
