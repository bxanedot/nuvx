#!/usr/bin/env python3
"""Safely install this portable instruction pack, without overwriting existing repo AGENTS.md.

Usage: python3 scripts/install_into_repo.py /path/to/nuvx
"""
from pathlib import Path
import shutil
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print('Usage: python3 scripts/install_into_repo.py /path/to/nuvx')
        return 2
    repo = Path(sys.argv[1]).resolve()
    src = Path(__file__).resolve().parents[1]
    if not (repo / '.git').exists():
        print(f'Not a git checkout: {repo}')
        return 1
    target = repo / 'docs' / 'rust-migration' / 'agent-pack'
    if target.exists():
        print(f'Target already exists; refusing overwrite: {target}')
        return 1
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, target, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    print(f'Installed {target}')
    print('Agent entrypoint: docs/rust-migration/agent-pack/START_HERE.md')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
