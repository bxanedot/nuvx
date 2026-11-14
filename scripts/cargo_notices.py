import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app" / "desktop" / "Cargo.toml"
SPDX_TEXTS = Path(__file__).with_name("spdx")
TARGETS = (
    "x86_64-pc-windows-msvc",
    "aarch64-pc-windows-msvc",
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "x86_64-apple-darwin",
    "aarch64-apple-darwin",
)
CUSTOM_TEXTS = {
    "Ubuntu-font-1.0": ROOT / "app" / "desktop" / "assets" / "licenses" / "epaint-fonts-UFL.txt",
}
NOTICE_FILE = re.compile(r"^(?:license|licence|copying|notice|copyright)(?:[-_.].*)?$", re.IGNORECASE)
TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+-]*")
OPERATORS = {"AND", "OR", "WITH"}


def license_ids(expression):
    return {value for value in TOKEN.findall(expression or "") if value not in OPERATORS}


def runtime_dependency_ids(metadata):
    resolved = metadata["resolve"]
    if not resolved or not resolved.get("root"):
        raise ValueError("Cargo metadata has no resolved root package")
    nodes = {node["id"]: node for node in resolved["nodes"]}
    root = resolved["root"]
    seen = {root}
    pending = [root]
    while pending:
        node = nodes[pending.pop()]
        for dep in node["deps"]:
            if not any(kind.get("kind") is None for kind in dep.get("dep_kinds", [])):
                continue
            package_id = dep["pkg"]
            if package_id in seen:
                continue
            seen.add(package_id)
            pending.append(package_id)
    return seen - {root}


def source_notices(package):
    manifest = Path(package["manifest_path"]).resolve()
    package_root = manifest.parent
    paths = set()
    license_file = package.get("license_file")
    if license_file:
        declared = Path(license_file)
        path = declared if declared.is_absolute() else package_root / declared
        path = path.resolve()
        if not path.is_relative_to(package_root) or not path.is_file():
            raise ValueError(f"Missing or out-of-package license_file for {package['name']} {package['version']}")
        paths.add(path)
    if not package_root.is_dir():
        raise ValueError(f"Cargo source is missing for {package['name']} {package['version']}")
    for path in package_root.iterdir():
        if path.is_file() and NOTICE_FILE.match(path.name):
            resolved = path.resolve()
            if not resolved.is_relative_to(package_root):
                raise ValueError(f"Out-of-package notice file for {package['name']} {package['version']}")
            paths.add(resolved)
    license_directory = package_root / "LICENSES"
    if license_directory.is_dir():
        for path in license_directory.rglob("*"):
            if path.is_file():
                resolved = path.resolve()
                if not resolved.is_relative_to(package_root):
                    raise ValueError(f"Out-of-package notice file for {package['name']} {package['version']}")
                paths.add(resolved)
    return sorted(paths, key=lambda path: path.name.casefold())


def metadata_for(target):
    result = subprocess.run(
        ["cargo", "metadata", "--locked", "--format-version", "1", "--manifest-path", str(MANIFEST), "--filter-platform", target],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return json.loads(result.stdout)


def collect_target_packages(target):
    metadata = metadata_for(target)
    packages = {package["id"]: package for package in metadata["packages"]}
    dependencies = runtime_dependency_ids(metadata)
    if dependencies - packages.keys():
        raise ValueError(f"Cargo metadata has unresolved packages for {target}")
    return [packages[package_id] for package_id in sorted(dependencies, key=lambda item: packages[item]["name"].casefold())]


def collect_notices(target_packages):
    notices = {}
    package_notice_ids = {}
    missing_custom = {}
    license_ids_used = set()
    for target, packages in target_packages.items():
        for package in packages:
            package_key = package.get("id") or (package["name"], package["version"])
            actual = source_notices(package)
            notice_ids = []
            for path in actual:
                content = path.read_bytes()
                notice_id = hashlib.sha256(content).hexdigest()[:16]
                entry = notices.setdefault(notice_id, {"content": content.decode("utf-8", errors="replace"), "packages": set(), "files": set()})
                entry["packages"].add(f"{package['name']} {package['version']}")
                entry["files"].add(path.relative_to(Path(package["manifest_path"]).resolve().parent).as_posix())
                notice_ids.append(notice_id)
            package_notice_ids.setdefault(package_key, set()).update(notice_ids)
            if not package.get("license") and not package.get("license_file"):
                missing_custom[f"{package['name']} {package['version']}"] = "missing Cargo license metadata"
                continue
            declared_ids = license_ids(package.get("license"))
            license_ids_used.update(declared_ids)
            for identifier in declared_ids:
                if identifier in OPERATORS or identifier.startswith("LicenseRef-"):
                    if identifier.startswith("LicenseRef-") and not actual:
                        missing_custom[f"{package['name']} {package['version']}"] = identifier
                    continue
                if identifier not in CUSTOM_TEXTS and not (SPDX_TEXTS / f"{identifier}.txt").is_file():
                    missing_custom[f"{package['name']} {package['version']}"] = identifier
    if missing_custom:
        details = ", ".join(f"{package}: {identifier}" for package, identifier in sorted(missing_custom.items()))
        raise ValueError(f"No source notice or bundled SPDX text for: {details}")
    license_texts = {}
    for identifier in sorted(license_ids_used):
        path = CUSTOM_TEXTS.get(identifier, SPDX_TEXTS / f"{identifier}.txt")
        if path.is_file():
            license_texts[identifier] = path.read_text(encoding="utf-8")
    return notices, package_notice_ids, license_texts


def cell(value):
    return str(value or "").replace("|", "\\|").replace("\n", " ").strip()


def render_notices(target_packages, license_texts, package_notices, notices=None):
    lines = [
        "# Nuvx desktop Cargo dependency notices",
        "",
        "Generated from the locked Cargo dependency graph for each listed target. Cargo license expressions, authors, and repositories are reproduced as package metadata; Cargo authors are not asserted to be copyright holders. Package license, notice, and copyright files are reproduced from the resolved crate source when present. An empty source notice file list means none was found in the package root or its LICENSES directory; declared SPDX texts appear below. SPDX texts use SPDX License List Data v3.29.0; the Ubuntu font text comes from the repository's epaint font notice.",
        "",
        "## Runtime dependency inventory",
        "",
    ]
    for target, packages in sorted(target_packages.items()):
        lines.extend((f"### `{target}`", "", "| Crate | Version | Declared license expression | Cargo authors | Cargo source | Repository | Source notice files |", "| --- | --- | --- | --- | --- | --- | --- |"))
        for package in sorted(packages, key=lambda item: (item["name"].casefold(), item["version"])):
            key = package.get("id") or (package["name"], package["version"])
            references = ", ".join(f"`{notice}`" for notice in sorted(package_notices.get(key, ())))
            authors = "; ".join(package.get("authors") or [])
            lines.append(
                "| " + " | ".join((
                    f"`{cell(package['name'])}`",
                    cell(package["version"]),
                    f"`{cell(package.get('license') or 'unspecified')}`",
                    cell(authors),
                    cell(package.get("source") or "path"),
                    cell(package.get("repository") or ""),
                    references,
                )) + " |"
            )
        lines.append("")
    if notices:
        lines.extend(("## Crate source notices", ""))
        for notice_id, entry in sorted(notices.items()):
            packages = ", ".join(f"`{name}`" for name in sorted(entry["packages"]))
            files = ", ".join(f"`{name}`" for name in sorted(entry["files"]))
            lines.extend((f"### `{notice_id}` · {files}", "", f"Found in: {packages}", "", "```text", entry["content"].rstrip(), "```", ""))
    lines.extend(("## Declared license texts", ""))
    for identifier, content in sorted(license_texts.items()):
        lines.extend((f"### `{identifier}`", "", "```text", content.rstrip(), "```", ""))
    return "\n".join(lines).rstrip() + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "app" / "desktop" / "assets" / "licenses" / "cargo-notices.txt")
    args = parser.parse_args()
    subprocess.run(["cargo", "fetch", "--locked", "--manifest-path", str(MANIFEST)], check=True)
    target_packages = {target: collect_target_packages(target) for target in TARGETS}
    notices, package_notice_ids, license_texts = collect_notices(target_packages)
    output = render_notices(target_packages, license_texts, package_notice_ids, notices)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8", newline="\n")
    print(f"Wrote {args.output} for {sum(map(len, target_packages.values()))} target-package entries")


if __name__ == "__main__":
    main()
