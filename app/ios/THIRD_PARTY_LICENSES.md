# Third-party licenses

nuvx is distributed by bxane. This document lists third-party components,
resources, and their licenses. Per-file dependency notices remain with their source.

## Components

| Component | License / retained text |
| --- | --- |
| InnerTubeX, revision `64c7df4d88d27e6964157b3b341f7ec70f88ea6e` | GPLv3; `vendor/innertubex/LICENSE` |
| QuackJS, revision `d867d98f9f2562009399cdfecd67e626a086f878` | GPL-3.0-only; `vendor/quackjs/LICENSE`; its fixture notices remain in `vendor/quackjs/THIRD_PARTY_NOTICES.md` |
| Orchard-derived analyzer by SFG545 and Kushagra Singh | AGPL-3.0-or-later; source headers in `native-core/src/analyzer/`; `AGPL-3.0.txt` |
| LastWave-native clarity specifications, revision `ec11a430fcf6e7f06bbae450e768d48f1d97d161` | GPLv3; header in `native-core/src/sound.rs`; `Lastwave-license.txt` |
| CPAL audio I/O | Apache-2.0; `Apache-2.0-CPAL.txt` |
| objc2 Apple bindings | MIT selected option; `MIT-objc2.txt`; upstream notes that Apple SDK terms may also apply |
| Symphonia and UniFFI | MPL-2.0; `MPL-2.0.txt` |
| Symphonia Opus adapter | MIT selected option; `MIT-Symphonia-Opus-Adapter.txt` |
| libopus | BSD-3-Clause; `BSD-3-Clause-Opus.txt` |
| libsoxr 0.1.3 by Rob Sykes | LGPL-2.1-or-later; `libsoxr-LGPL-2.1.txt`, `libsoxr-notice.txt`; full vendored source |
| ebur128 0.1.10 | MIT; `ebur128-MIT.txt` |
| Kotlin, Ktor, kotlinx and KMP-NativeCoroutines | Apache-2.0; `Apache-2.0.txt`; versions in Gradle manifests |
| Sparkle 2.10.0, macOS only | MIT and bundled external notices; `Sparkle-LICENSE.txt` |
| yt-dlp/ejs 0.8.0 solver | Unlicense; bundled meriyah ISC and astring MIT notices in `yt-ejs-dependencies.txt` and original JS headers |
| Unicode data, webpki certificate roots, zlib-rs | Unicode-3.0, CDLA-Permissive-2.0 and Zlib; `Cargo-data-licenses.txt` |
| Material Icons Rounded | Apache-2.0; `material-icons.txt` |
| Phosphor Icons | MIT; `PhosphorIcons-MIT.txt` |
| Localization resources | GPLv3; `GPL-3.0.txt` |

Filenames without a directory above refer to
`core/apple/resources/Resources/AudioLicenses/`. `native-core/Cargo.lock` pins the Rust
dependency graph; transitive dependencies keep their own upstream license terms
and notices. No claim is made that every dependency has the same license.

The analyzer's AGPL terms continue to govern the Orchard-derived portions of
the GPLv3/AGPLv3 combined work. Review the applicable licenses before distributing
binaries. libsoxr is statically linked: distributors must supply the relinkable
materials and any installation information required by LGPL-2.1, not merely
an acknowledgement screen.

## Bundled analysis models

These three MIT-licensed model resources are included for local installation,
with their pinned size and SHA-256 verified.
ONNX exporter debug-stack metadata is omitted from the beat model; its weights
and computation are unchanged.

| Resource | Size | SHA-256 |
| --- | ---: | --- |
| `beat_this.onnx` | 22,650,399 B | `9b5492b7b3c66db3f1e189a33631be84773a4ec7e8661fcf0727d89cca0fe49f` |
| `beat_this_final0_coreml.aar` | 18,826,124 B | `cd00f20bd602f4d1532f0b683fe418f278affead3494f954032039d902b5b88f` |
| `vocals_umxhq_int8.onnx` | 23,209,009 B | `7eba6bebce0cc2e2d3b5c0e490a8074e66ce27f64170c6802b245740dcaf21ef` |

Beat This! originates from <https://github.com/CPJKU/beat_this>, final0 checkpoint.
The Core ML archive compiles the same checkpoint, with attention split into
150-row query blocks. open-unmix uses the official SigSep/Inria umxhq vocals
checkpoint by Stöter and Liutkus, Zenodo record 3370489.
`MIT-Model-Weights.txt` contains the model notices. Beat This! notes that
some training datasets may have separate terms.

Radio Browser supplies third-party station metadata and stream links
(<https://www.radio-browser.info/>). Individual streams and music remain their
providers' content; no music recordings are bundled.

## Trademark notice

Service names such as YouTube, Spotify, Apple Music and Dolby identify
integrations or technology, not nuvx ownership of those trademarks.
