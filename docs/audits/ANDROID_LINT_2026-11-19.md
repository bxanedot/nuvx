# Android lint audit, 2026-11-19

`./gradlew :app:lintDevDebug` on `main` (phone app, dev debug variant): **565 errors, 632 warnings, 7 hints.** Lint is not part of CI, and the release build only runs the "vital" subset, so none of this blocks a release. This is what the numbers are.

## Errors (565)

| Count | Rule | What it is | Action |
|---:|---|---|---|
| 431 | `UnsafeOptInUsageError` | The code uses Media3's `@UnstableApi` classes without a per-file `@OptIn`. The whole playback stack (gapless, crossfade, DSP, caching) depends on them on purpose, and Media3 is pinned. | None. A project-wide opt-in would hide the findings; adding 431 annotations changes nothing at runtime. Revisit when Media3 is upgraded. |
| 74 | `LocalContextGetResourceValueCall` | `LocalContext.current.resources.getX()` inside Compose does not recompose on a configuration change. | Low risk (language and font-scale changes recreate the activity). Fix opportunistically with `stringResource`/`dimensionResource`. |
| 29 | `MissingQuantity` | Plural strings in some languages lack a plural form that language needs (for example `few`, `many`). | Real polish for those languages: the English fallback shows. Needs a translator pass. |
| 13 | `StateFlowValueCalledInComposition` | `StateFlow.value` read inside composition. Checked all 13: dialogs that read the starting value of a form field once (`AddonBlockedAlert`, `RemoteStorageSettingsDialogs`, `DiscordScreen`, `AudioOutput`). | None: reading once is the intent. |
| 9 | `NewApi` | Calls above the min SDK (26): USB bit-perfect (API 34), offload (29), Bluetooth alias (30), `RuntimeShader` (33). Checked each: USB mode can only be chosen on Android 14+ (`SettingsSheet`), the Bluetooth read is inside a `try/catch Throwable`, shaders are only requested on 33+. | None: guarded where the user can reach them, lint cannot see through that. |
| 9 | other | locale reads and similar | Minor. |

## Warnings (632)

Mostly style: 287 `UseKtx` (use a Kotlin extension), 88 `MissingQuantity` (same as above, the warning grade), 71 `UnusedResources`, 23 `UnusedQuantity`, 20 `PrivateResource`, 14 `ModifierParameter`, 12 `RtlHardcoded`. The 71 unused resources are the only ones worth removing (smaller APK); run `UnusedResources` on a release variant first, because some strings are used only by other flavors (TV, Auto, Wear).

## Verdict

No correctness bug found in the 565. Nothing was changed for this audit. The two items worth doing next: the `MissingQuantity` plurals, and removing unused resources.
