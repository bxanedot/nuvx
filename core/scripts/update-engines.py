#!/usr/bin/env python3
"""Propose pinned engine updates; never executes downloaded upstream code."""
import argparse
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

INNER_REPOSITORY = "MetrolistGroup/innertubex"
EXCOR_REPOSITORY = "bxanedot/eXcor"
VENDOR_PATHS = ("build.gradle.kts", "gradle.properties", "cleanroom-src", "fixtures/cleanroom", "LICENSE", "NOTICE")
SHA = re.compile(r"[0-9a-f]{40}")
STABLE = re.compile(r"v?(\d+)\.(\d+)\.(\d+)")
MAX_ARCHIVE = 32 * 1024 * 1024
MAX_EXPANDED = 100 * 1024 * 1024


class RecoveryError(Exception):
    def __init__(self, directory):
        self.directory = directory


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        parsed = urlsplit(new_url)
        if parsed.scheme != "https" or parsed.hostname not in {"api.github.com", "codeload.github.com"}:
            raise ValueError("Unexpected GitHub download redirect")
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected and parsed.hostname != urlsplit(request.full_url).hostname:
            redirected.remove_header("Authorization")
        return redirected


def fetch(path, *, archive=False):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "nuvx-engine-updater", "X-GitHub-Api-Version": "2022-11-28"}
    # Archives are public: never forward a repository token to the archive host.
    if not archive and os.environ.get("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    limit = MAX_ARCHIVE if archive else 2 * 1024 * 1024
    request = Request("https://api.github.com/" + path, headers=headers)
    with build_opener(SafeRedirect()).open(request, timeout=30) as response:
        result = response.read(limit + 1)
    if len(result) > limit:
        raise ValueError("GitHub response exceeds updater size limit")
    return result if archive else json.loads(result)


def stable_release(releases, current):
    match = STABLE.fullmatch(current)
    if not match:
        raise ValueError("Current InnerTubeX pin is not a stable version")
    current_version = tuple(map(int, match.groups()))
    candidates = []
    for release in releases:
        tag = release.get("tag_name", "")
        version = STABLE.fullmatch(tag)
        if version and not release.get("draft") and not release.get("prerelease"):
            number = tuple(map(int, version.groups()))
            if number > current_version:
                candidates.append((number, tag))
    return max(candidates)[1] if candidates else current


def catalog_version(text, name):
    matches = re.findall(r'^' + re.escape(name) + r'\s*=\s*"([^"\n]+)"\s*$', text, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Expected one catalog version for " + name)
    return matches[0]


def replace_version(text, name, value):
    if not re.fullmatch(r"[A-Za-z0-9.+_-]{1,100}", value):
        raise ValueError("Unsafe engine version")
    return re.sub(r'^(' + re.escape(name) + r'\s*=\s*)"[^"\n]+"', lambda m: m[1] + '"' + value + '"', text, flags=re.MULTILINE)


def clean_tree(root):
    result = subprocess.run(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root,
                            check=True, capture_output=True, text=True)
    if result.stdout:
        raise ValueError("Working tree is not clean; preserve and commit local work before updating")


def safe_destination(root, path):
    destination = root.joinpath(*PurePosixPath(path).parts)
    if not destination.resolve().is_relative_to(root.resolve()):
        raise ValueError("Update destination escapes repository")
    for parent in [destination, *destination.parents]:
        if parent == root.parent:
            break
        if parent.is_symlink():
            raise ValueError("Update destination contains a symbolic link")
    if destination.is_dir() and any(item.is_symlink() for item in destination.rglob("*")):
        raise ValueError("Existing upstream source contains symbolic links")
    return destination


def unpack_source(data, destination):
    seen = set()
    prefix = None
    expanded = 0
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for index, member in enumerate(archive):
            expanded += member.size
            if index >= 20_000 or member.size > MAX_ARCHIVE or expanded > MAX_EXPANDED:
                raise ValueError("Upstream archive exceeds extraction limits")
            parts = PurePosixPath(member.name).parts
            if not parts or member.name.startswith("/") or "\\" in member.name or any(part in {".", ".."} or ":" in part for part in parts):
                raise ValueError("Unsafe path in upstream archive")
            if prefix is None:
                prefix = parts[0]
            if parts[0] != prefix:
                raise ValueError("Multiple roots in upstream archive")
            if not (member.isfile() or member.isdir()):
                raise ValueError("Links and special files are forbidden in upstream archives")
            relative = PurePosixPath(*parts[1:]).as_posix()
            if not any(relative == allowed or relative.startswith(allowed + "/") for allowed in VENDOR_PATHS):
                continue
            if relative in seen:
                raise ValueError("Duplicate upstream archive path")
            seen.add(relative)
            target = safe_destination(destination, relative)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o755 if member.mode & 0o111 else 0o644)
    for path in VENDOR_PATHS:
        target = destination / path
        if (path in {"cleanroom-src", "fixtures/cleanroom"} and not target.is_dir()) or (path not in {"cleanroom-src", "fixtures/cleanroom"} and not target.is_file()):
            raise ValueError("Upstream archive is missing required source or licensing files")


def install_staged(root, changes, backup):
    """Rollback all replaced paths on failure, retaining recovery data if rollback fails."""
    applied = []
    try:
        for index, (relative, staged) in enumerate(changes):
            target = safe_destination(root, relative)
            old = backup / str(index)
            existed = target.exists()
            if existed:
                os.replace(target, old)
            applied.append((target, old, existed))
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staged, target)
    except Exception:
        try:
            for target, old, existed in reversed(applied):
                if target.is_dir():
                    shutil.rmtree(target)
                elif target.exists():
                    target.unlink()
                if existed:
                    os.replace(old, target)
        except Exception:
            raise RecoveryError(backup.parent) from None
        raise


def metadata_for(root):
    path = safe_destination(root, "core/engines/UPSTREAM.json")
    metadata = json.loads(path.read_text(encoding="utf-8-sig"))
    if metadata.get("repository") != EXCOR_REPOSITORY or metadata.get("paths") != list(VENDOR_PATHS) or not SHA.fullmatch(metadata.get("commit", "")):
        raise ValueError("Unsupported upstream metadata")
    return metadata


def source_version(source):
    versions = re.findall(r'^version\s*=\s*"([A-Za-z0-9.+_-]{1,100})"\s*$',
                          (source / "build.gradle.kts").read_text(encoding="utf-8"), re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("Expected one literal upstream library version")
    return versions[0]


def cached_source(root, commit):
    if not SHA.fullmatch(commit):
        raise ValueError("Invalid engine commit")
    source = safe_destination(root, ".gradle/engines/excor-" + commit)
    marker = source / ".nuvx-source.json"
    if not marker.is_file() or json.loads(marker.read_text()) != {"repository": EXCOR_REPOSITORY, "commit": commit}:
        raise ValueError("No complete cached source; run an online build first")
    for path in VENDOR_PATHS:
        target = source / path
        if not target.exists():
            raise ValueError("Incomplete cached source")
    return source


def fetch_latest(root, offline=False):
    """Resolve upstream links into ignored, immutable source directories, never tracked files."""
    root = root.resolve()
    metadata_for(root)
    cache = safe_destination(root, ".gradle/engines")
    cache.mkdir(parents=True, exist_ok=True)
    properties = cache / "resolved.properties"
    if offline:
        resolved = dict(line.split("=", 1) for line in properties.read_text().splitlines() if line)
        if not STABLE.fullmatch(resolved.get("innertubex", "")):
            raise ValueError("No cached InnerTubeX release")
        source = cached_source(root, resolved.get("excor.commit", ""))
    else:
        catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
        current = catalog_version(catalog, "innertubex")
        releases = []
        for page in range(1, 11):
            batch = fetch(f"repos/{INNER_REPOSITORY}/releases?per_page=100&page={page}")
            releases.extend(batch)
            if len(batch) < 100:
                break
        inner = stable_release(releases, current)
        repository = fetch(f"repos/{EXCOR_REPOSITORY}")
        branch = repository.get("default_branch")
        if not isinstance(branch, str) or not branch:
            raise ValueError("Missing upstream default branch")
        commit = fetch(f"repos/{EXCOR_REPOSITORY}/commits/{quote(branch, safe='')}").get("sha", "")
        if not SHA.fullmatch(commit):
            raise ValueError("GitHub did not return an immutable commit")
        destination = safe_destination(root, ".gradle/engines/excor-" + commit)
        if not destination.exists():
            data = fetch(f"repos/{EXCOR_REPOSITORY}/tarball/{commit}", archive=True)
            with tempfile.TemporaryDirectory(prefix=".nuvx-engine-fetch-", dir=cache) as temporary:
                staged = Path(temporary) / "source"
                staged.mkdir()
                unpack_source(data, staged)
                (staged / ".nuvx-source.json").write_text(json.dumps({"repository": EXCOR_REPOSITORY, "commit": commit}))
                try:
                    os.rename(staged, destination)
                except OSError:
                    # Another build may have finished fetching the same immutable revision.
                    cached_source(root, commit)
        source = cached_source(root, commit)
        resolved = {"innertubex": inner, "excor": source_version(source), "excor.commit": commit}
    template = (root / "core/engines/excor.settings.gradle.kts").read_bytes()
    settings = safe_destination(root, str(source.relative_to(root) / "settings.gradle.kts"))
    if not settings.exists() or settings.read_bytes() != template:
        settings.write_bytes(template)
    if source_version(source) != resolved.get("excor"):
        raise ValueError("Cached engine version mismatch")
    contents = "".join(key + "=" + value + "\n" for key, value in resolved.items())
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=cache, delete=False) as output:
        output.write(contents)
        staged_properties = Path(output.name)
    os.replace(staged_properties, properties)
    return resolved


def update(root):
    root = root.resolve()
    clean_tree(root)
    metadata = metadata_for(root)
    catalog_path = safe_destination(root, "gradle/libs.versions.toml")
    catalog = catalog_path.read_text(encoding="utf-8")
    resolved = fetch_latest(root)
    new_catalog = replace_version(replace_version(catalog, "innertubex", resolved["innertubex"]), "excor", resolved["excor"])
    messages = []
    if resolved["innertubex"] != catalog_version(catalog, "innertubex"):
        messages.append("InnerTubeX: " + catalog_version(catalog, "innertubex") + " -> " + resolved["innertubex"])
    if resolved["excor.commit"] != metadata["commit"]:
        messages.append("eXcor: " + metadata["commit"] + " -> " + resolved["excor.commit"])
    clean_tree(root)
    stage = Path(tempfile.mkdtemp(prefix=".nuvx-engine-update-", dir=root / ".gradle")).resolve()
    keep_recovery = False
    try:
        changes = []
        if metadata["commit"] != resolved["excor.commit"]:
            metadata["commit"] = resolved["excor.commit"]
            staged = stage / "UPSTREAM.json"
            staged.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
            changes.append(("core/engines/UPSTREAM.json", staged))
        if new_catalog != catalog:
            staged = stage / "libs.versions.toml"
            staged.write_text(new_catalog, encoding="utf-8")
            changes.append(("gradle/libs.versions.toml", staged))
        backup = stage / "backup"
        backup.mkdir()
        try:
            install_staged(root, changes, backup)
        except Exception as error:
            keep_recovery = isinstance(error, RecoveryError) or any(backup.iterdir())
            raise
        return messages
    finally:
        if not keep_recovery:
            if stage.parent != root / ".gradle" or not stage.name.startswith(".nuvx-engine-update-"):
                raise ValueError("Unexpected staging path")
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--fetch", action="store_true", help="Resolve latest engines into the ignored build cache")
    parser.add_argument("--offline", action="store_true", help="Use the last complete build-cache resolution")
    args = parser.parse_args()
    try:
        if args.fetch:
            resolved = fetch_latest(args.root, args.offline)
            print("InnerTubeX " + resolved["innertubex"] + "; eXcor " + resolved["excor.commit"])
        else:
            if args.offline:
                raise ValueError("Update proposals require an online resolution")
            messages = update(args.root)
            print("\n".join(messages) if messages else "Engine pins are current.")
    except RecoveryError as error:
        parser.exit(1, "Update rollback needs inspection; recovery copies retained at " + str(error.directory) + "\n")
    except Exception as error:
        parser.exit(1, "Engine resolution failed (" + type(error).__name__ + "); no incomplete source was accepted.\n")


if __name__ == "__main__":
    main()
