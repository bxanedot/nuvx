#!/usr/bin/env python3
"""Validate the local instruction pack completeness, not the actual Rust migration."""
import json
from pathlib import Path

p=Path(__file__).resolve().parents[1]
required=['START_HERE.md','MASTER_RULES.md','AUTORUN_PROTOCOL.md','TASK_INDEX.md',
          'templates/STATUS.json','templates/CONTRACT.md','templates/HANDOFF.md',
          'templates/PARITY_REPORT.md','templates/FEATURE_MATRIX.csv',
          'templates/UI_PARITY.csv','templates/METRICS.csv','templates/BLOCKERS.csv',
          'RUST_ANYTHING_INTEGRATION.md','templates/TOOL_ELIGIBILITY.csv']
for f in required:
    assert (p/f).is_file(),f'Missing {f}'
files=sorted((p/'tasks').glob('*.md'))
assert len(files)==27,f'Expected 27 task files; found {len(files)}'
for i,file in enumerate(files,0):
    assert file.name.startswith(f'{i:02}-'),f'Wrong sequence: {file.name}'
    body=file.read_text()
    assert 'next numbered task' in body and 'without waiting' in body,f'No chaining instruction: {file.name}'
status=json.loads((p/'templates/STATUS.json').read_text())
assert len(status['tasks'])==27
assert [r['id'] for r in status['tasks']]==[f'{i:02}' for i in range(27)]
print('VALID: 27 ordered task files (00-26), 27 status rows, continuous-run instructions and all required templates.')
