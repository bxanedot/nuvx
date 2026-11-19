# Parity verification report — TEMPLATE

## Target
- Task and subtarget:
- Old implementation (commit, path):
- New implementation (commit, path):
- Platform/runtime/device:
- Recorded build/configuration:

## Visual/UX reference
- Original screenshot/recording:
- Replacement screenshot/recording:
- Viewport, DPI, OS version, theme, font scale, language, reduced motion:
- Pixel-comparison method/threshold and actual differences:
- Animation and gesture comparison:
- Accessibility/focus/navigation behavior:
- Result: PASS / FAIL / NOT_VERIFIED / BLOCKED

## Functional and data compatibility
- Original behavior fixtures:
- Rust behavior fixtures:
- Test commands and real output:
- Upgrade/rollback/checkpoint coverage:
- Result: PASS / FAIL / NOT_VERIFIED / BLOCKED

## Benchmarks
| Metric | Original | New | Environment/method | Outcome |
|---|---:|---:|---|---|
| Download size | | | | |
| Installed size | | | | |
| Cold start | | | | |
| Idle RAM | | | | |
| Playback CPU | | | | |
| Energy/battery | | | | |
| Frame latency | | | | |

## Final disposition
- CUTOVER_ALLOWED / RETAIN_OLD_UI / BLOCKED / NOT_VERIFIED
- Revert path:
- Outstanding verification:
