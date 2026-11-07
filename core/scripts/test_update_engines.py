import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("engine_updates", Path(__file__).with_name("update-engines.py"))
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
OLD, NEW = "a" * 40, "b" * 40


def source_archive(extra=()):
    files = {
        "build.gradle.kts": 'version = "0.3.0"\n',
        "gradle.properties": "org.gradle.jvmargs=-Xmx1g\n",
        "cleanroom-src/commonMain/Smoke.kt": "// synthetic fixture\n",
        "fixtures/cleanroom/example.json": "{}",
        "LICENSE": "synthetic licence",
        "NOTICE": "synthetic notice",
        "historical-engine/Excluded.kt": "not imported",
    }
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        for path, value in files.items():
            data = value.encode()
            info = tarfile.TarInfo("upstream/" + path)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        for info in extra:
            archive.addfile(info)
    return output.getvalue()


class EngineUpdatesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "core/engines").mkdir(parents=True)
        (self.root / "gradle").mkdir()
        self.metadata = self.root / "core/engines/UPSTREAM.json"
        self.metadata.write_text(json.dumps({"repository": engine.EXCOR_REPOSITORY, "commit": OLD, "paths": list(engine.VENDOR_PATHS)}))
        self.catalog = self.root / "gradle/libs.versions.toml"
        self.catalog.write_text('innertubex = "v0.11.2"\nexcor = "0.2.0"\n')
        (self.root / "core/engines/excor.settings.gradle.kts").write_text("// Nuvx settings template\n")
        self.calls = []

    def fetch(self, path, *, archive=False):
        self.calls.append(path)
        if "/releases?" in path:
            return [{"tag_name": "v0.12.0", "draft": False, "prerelease": False}]
        if path == "repos/" + engine.EXCOR_REPOSITORY:
            return {"default_branch": "main"}
        if "/commits/" in path:
            return {"sha": NEW}
        if "/tarball/" in path:
            self.assertTrue(archive)
            return source_archive()
        self.fail("Unexpected upstream API request: " + path)

    def test_latest_stable_skips_previews_and_never_downgrades(self):
        releases = [{"tag_name": "v0.12.0", "prerelease": True}, {"tag_name": "v0.11.3"}, {"tag_name": "v9.0.0", "draft": True}]
        self.assertEqual("v0.11.3", engine.stable_release(releases, "v0.11.2"))
        self.assertEqual("v0.12.0", engine.stable_release(releases, "v0.12.0"))

    def test_build_fetches_latest_into_cache_without_modifying_tracked_pins(self):
        original = self.metadata.read_bytes(), self.catalog.read_bytes()
        with patch.object(engine, "fetch", side_effect=self.fetch):
            resolved = engine.fetch_latest(self.root)
            engine.fetch_latest(self.root)
        source = self.root / ".gradle/engines" / ("excor-" + NEW)
        self.assertEqual("v0.12.0", resolved["innertubex"])
        self.assertEqual("0.3.0", resolved["excor"])
        self.assertTrue((source / "cleanroom-src/commonMain/Smoke.kt").is_file())
        self.assertFalse((source / "historical-engine").exists())
        self.assertEqual("// Nuvx settings template\n", (source / "settings.gradle.kts").read_text())
        self.assertEqual(original, (self.metadata.read_bytes(), self.catalog.read_bytes()))
        self.assertEqual(1, sum("/tarball/" in call for call in self.calls))

    def test_offline_uses_complete_cached_resolution_without_network(self):
        with patch.object(engine, "fetch", side_effect=self.fetch):
            expected = engine.fetch_latest(self.root)
        with patch.object(engine, "fetch", side_effect=AssertionError("offline network")):
            self.assertEqual(expected, engine.fetch_latest(self.root, offline=True))

    def test_offline_without_cache_fails(self):
        with self.assertRaises(FileNotFoundError):
            engine.fetch_latest(self.root, offline=True)

    def test_update_proposes_only_pin_files_and_retains_user_content(self):
        (self.root / "user.txt").write_text("keep me")
        with patch.object(engine, "clean_tree"), patch.object(engine, "fetch", side_effect=self.fetch):
            messages = engine.update(self.root)
            self.assertEqual([], engine.update(self.root))
        self.assertEqual(2, len(messages))
        self.assertEqual(NEW, json.loads(self.metadata.read_text())["commit"])
        self.assertIn('innertubex = "v0.12.0"', self.catalog.read_text())
        self.assertIn('excor = "0.3.0"', self.catalog.read_text())
        self.assertEqual("keep me", (self.root / "user.txt").read_text())

    def test_dirty_repository_stops_before_network(self):
        with patch.object(engine.subprocess, "run") as run, patch.object(engine, "fetch") as fetch:
            run.return_value.stdout = " M user.txt\n"
            with self.assertRaises(ValueError):
                engine.update(self.root)
            fetch.assert_not_called()

    def test_archive_rejects_traversal_links_and_multiple_roots(self):
        for path, kind in [("upstream/../../escape", tarfile.REGTYPE), ("upstream/cleanroom-src/link", tarfile.SYMTYPE), ("other/file", tarfile.REGTYPE)]:
            info = tarfile.TarInfo(path)
            info.type = kind
            info.linkname = "/outside"
            with self.subTest(path=path), self.assertRaises(ValueError):
                engine.unpack_source(source_archive([info]), self.root / "extract")
        self.assertFalse((self.root.parent / "escape").exists())

    def test_archive_bounds_apply_to_unselected_files_too(self):
        info = tarfile.TarInfo("upstream/unrelated/huge.bin")
        info.size = engine.MAX_ARCHIVE + 1
        with self.assertRaises(ValueError):
            engine.unpack_source(source_archive([info]), self.root / "extract")
    def test_wrong_repository_metadata_is_rejected(self):
        self.metadata.write_text(json.dumps({"repository": "someone/else", "commit": OLD, "paths": list(engine.VENDOR_PATHS)}))
        with patch.object(engine, "fetch") as fetch, self.assertRaises(ValueError):
            engine.fetch_latest(self.root)
        fetch.assert_not_called()

    def test_invalid_upstream_commit_does_not_publish_resolution(self):
        def invalid(path, **options):
            return {"sha": "not-a-commit"} if "/commits/" in path else self.fetch(path, **options)
        with patch.object(engine, "fetch", side_effect=invalid), self.assertRaises(ValueError):
            engine.fetch_latest(self.root)
        self.assertFalse((self.root / ".gradle/engines/resolved.properties").exists())

    def test_failed_pin_replacement_rolls_back_existing_files(self):
        (self.root / "one").write_text("old one")
        (self.root / "two").write_text("old two")
        stage = self.root / "stage"
        stage.mkdir()
        (stage / "one").write_text("new one")
        (stage / "two").write_text("new two")
        backup = self.root / "backup"
        backup.mkdir()
        replace = engine.os.replace
        calls = 0
        def fail_third(source, target):
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError("synthetic storage failure")
            return replace(source, target)
        with patch.object(engine.os, "replace", side_effect=fail_third), self.assertRaises(OSError):
            engine.install_staged(self.root, [("one", stage / "one"), ("two", stage / "two")], backup)
        self.assertEqual("old one", (self.root / "one").read_text())
        self.assertEqual("old two", (self.root / "two").read_text())


if __name__ == "__main__":
    unittest.main()
