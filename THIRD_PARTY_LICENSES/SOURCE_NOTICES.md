# Source-file notices

These notices were relocated from source comments. Source paths identify their scope.

## `app/android/mobile/src/main/cpp/CMakeLists.txt`

````text
# Bit-perfect output (see playback/audio/bitperfect). AAudio exclusive streams
# through Oboe, built from a pinned release with its format/rate conversion
# compiled out: a stream that needed converting is one this library rejects.
# Oboe is Apache-2.0; exclusive_output.cpp is adapted from Mornye (LGPL-3.0).

# Direct USB output: a userspace USB Audio Class driver on libusb, so a DAC
# can be streamed to without Android's mixer on any Android version. libusb
# (LGPL-2.1) is built from its pinned release using the source list and
# config.h it ships for Android (android/jni/libusb.mk). The transport is
# adapted from Mornye's Rust driver (LGPL-3.0).
````

## `app/android/mobile/src/main/cpp/bitperfect/exclusive_output.cpp`

````text
// SPDX-License-Identifier: LGPL-3.0-only
// Exact-rate PCM through an AAudio exclusive stream (Oboe), for players with
// a built-in DAC. Exclusive is only a request: Android may grant a shared
// stream instead, and a shared stream is mixed. The stream is accepted only
// when the API, sharing mode, rate, channel count and format all came back
// exactly as asked; otherwise it is closed and the caller uses normal output.
````

## `app/android/mobile/src/main/cpp/bitperfect/usb_stream.h`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/cpp/bitperfect/usb_transport.cpp`

````text
// SPDX-License-Identifier: LGPL-3.0-only
// A mixer-free USB Audio Class output: Android grants permission and opens
// the device; one worker thread here owns libusb, the claimed interfaces,
// four isochronous OUT transfers and the explicit feedback transfer. No
// resampling or gain happens anywhere below the queue.
//
// Every raw libusb pointer belongs to the worker. Transfer buffers keep
// stable addresses until libusb acknowledges completion or cancellation,
// including on detach and error paths, before any memory is freed. Class
// control requests (the DAC's volume) are queued to the same worker rather
// than issued from another thread.
//
// Descriptor parsing and the choice of alternate setting happen in Kotlin
// (UsbDescriptors.kt); this side is handed the candidates in preference order
// and opens the first one the device accepts.
````

## `app/android/mobile/src/main/cpp/dsd/dst/decoder.c`

````text
// SPDX-License-Identifier: LGPL-2.1-or-later

/**
 * @file decoder.c
 * @brief DST decoder - single-frame public API and core decoding algorithm
 *
 * ISO/IEC 14496-3 Part 3 Subpart 10: Technical description of lossless
 * coding of oversampled audio
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
````

## `app/android/mobile/src/main/cpp/dsd/dst/jni.cpp`

````text
// SPDX-License-Identifier: LGPL-2.1-or-later
````

## `app/android/mobile/src/main/cpp/jni/analysis_jni.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI bridge to the whole-track DSP analyzer and its resampler.
//
// AnalysisResult carries about twenty fields including strings, which is more
// than is worth marshalling field by field through JNI. It is serialized to
// JSON instead: analysis runs once per track, so the cost is irrelevant
// beside the decode around it, and a string is far easier to log and to test
// against than a hand-packed buffer.
//
// Only the subset the transition policy actually reads is emitted. Chroma,
// the mid and high energy curves, loudness, peak and dynamic range are
// computed by the analyzer but nothing downstream consumes them, and emitting
// them would mean three more float arrays per track for no reader.
````

## `app/android/mobile/src/main/cpp/jni/loopmix_jni.cpp`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
// JNI bridge for the Automix loop mix renderer (native/loopmix/). Every call
// but render() is made while the mix is being set up; render() runs on the
// player's playback thread, inside its audio sink.
````

## `app/android/mobile/src/main/cpp/jni/mel_jni.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI bridge to the mel front end.
//
// Kept as thin as a bridge can be: it copies the sample array in, calls
// ComputeBeatSpectrogram, and hands back a flat float[]. No policy, no
// buffering, no threading -- all of that belongs on the Kotlin side where it
// can be cancelled and tested.
````

## `app/android/mobile/src/main/cpp/jni/vocal_jni.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI bridge to the vocal-separation front end.
//
// Unlike the mel front end this one is stereo and linear-frequency, because
// that is what open-unmix was trained on. The layout it produces is bin-major
// rather than frame-major specifically so it matches the model's tensor shape
// [1, 2, bins, frames] with no transpose on the Kotlin side.
````

## `app/android/mobile/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegDecoderException.java`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/AudioOutputStatus.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/DirectAudioProbe.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/OutputNegotiator.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/BitPerfectAudioSink.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/BitPerfectOutput.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/BitPerfectPcm.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/BitPerfectRoutingSink.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/BitPerfectTransport.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/DacFormatConverter.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/DacVolume.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/ExclusiveTransport.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/MixerTransport.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/usb/UsbDescriptors.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/usb/UsbDirectDevices.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/usb/UsbDirectTransport.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bitperfect/usb/UsbVolume.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/audio/bluetooth/BluetoothAudioTracker.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/AudioDecoder.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/MixWaveform.kt`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/TrackAnalysisRegionProcessor.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/TrackAnalysisRenditionSelector.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/TrackAnalysisSourceResolver.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/playback/smart/TrackAnalyzer.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/YtMusicWebSessionScreen.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/DacVolumeHud.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/Backdrop.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/BackdropEffectScope.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/DrawBackdropModifier.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/Platform.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/RuntimeShader.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/RuntimeShaderCache.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/backdrops/Backdrop.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/backdrops/CanvasBackdrop.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/backdrops/CombinedBackdrop.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/backdrops/EmptyBackdrop.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/backdrops/LayerBackdrop.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/backdrops/LayerBackdropModifier.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/effects/Blur.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/effects/ColorFilter.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/effects/Lens.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/effects/RenderEffect.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/highlight/Highlight.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/highlight/HighlightModifier.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/highlight/HighlightStyle.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/InverseLayerScope.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/LayerRecorder.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/Outline.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/Paint.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/RenderEffect.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/Shaders.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/internal/ShapeProvider.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/shadow/InnerShadow.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/shadow/InnerShadowModifier.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/shadow/Shadow.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/backdrop/shadow/ShadowModifier.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/floatingtabbar/FloatingTabBar.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/floatingtabbar/FloatingTabBarScrollConnection.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/components/floatingtabbar/GlassSelectionPill.kt`

````text
// SPDX-License-Identifier: MIT
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/intro/NuvxIntro.kt`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
````

## `app/android/mobile/src/main/java/com/bxane/nuvx/ui/screens/RadioScreen.kt`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
````

## `app/android/mobile/src/main/java/org/schabi/newpipe/extractor/utils/Utils.java`

````text
/*
 * Vendored verbatim from NewPipeExtractor v0.26.3 (GPL-3.0-or-later), the version
 * pinned in app/build.gradle.kts, and then changed in exactly two places:
 * [encodeUrlUtf8] and [decodeUrlUtf8]. Nothing else in this file differs from
 * upstream, deliberately — see the note on those two methods for why the copy
 * exists at all, and keep any future edits confined to them so the file stays
 * diffable against the tag it came from.
 *
 * This copy does not merely sit alongside the jar's: app/build.gradle.kts strips
 * Utils.class out of the NewPipeExtractor artifact, because two definitions of one
 * class cannot both reach dex merging in a release build. Removing that strip task
 * breaks prodRelease, not just this file.
 *
 * Upstream:
 * https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.3/extractor/src/main/java/org/schabi/newpipe/extractor/utils/Utils.java
 */
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/AudioPipelineTelemetryTest.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/AudioTelemetryHardeningTest.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/DirectAudioStreamingRegressionTest.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/NerdStatsRegressionTest.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/OutputNegotiatorTest.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/bitperfect/BitPerfectPcmTest.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/bitperfect/usb/UsbAudioLogicTest.kt`

````text
// SPDX-License-Identifier: LGPL-3.0-only
````

## `app/android/mobile/src/test/java/com/bxane/nuvx/playback/audio/bluetooth/BluetoothAudioTrackerTest.kt`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `app/desktop/assets/ejs/yt.solver.core.min.js`

````text
/*!
 * SPDX-License-Identifier: Unlicense
 * This file was automatically generated by https://github.com/yt-dlp/ejs
 */
````

## `app/desktop/assets/ejs/yt.solver.lib.min.js`

````text
/*!
 * SPDX-License-Identifier: Unlicense
 * This file was automatically generated by https://github.com/yt-dlp/ejs
 *
 * Bundled Dependencies:
 *
 * ---
 * Name: meriyah
 * Version: 6.1.4
 * License: ISC
 * Repository: git+https://github.com/meriyah/meriyah.git
 * Author: Kenny F. (https://github.com/KFlash)
 *
 * ISC License
 *
 * Copyright (c) 2019 and later, KFlash and others.
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 *
 * ---
 *
 * ---
 * Name: astring
 * Version: 1.9.0
 * License: MIT
 * Repository: https://github.com/davidbonnet/astring.git
 * Author: David Bonnet <david@bonnet.cc>
 *
 * Copyright (c) 2015, David Bonnet <david@bonnet.cc>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ---
 */
````

## `app/desktop/vendor/glib/src/boxed.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/boxed_any_object.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/boxed_inline.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/bridged_logging.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/byte_array.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/bytes.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/char.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/checksum.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/clone.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/closure.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/collections/list.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/collections/mod.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/collections/ptr_slice.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/collections/slice.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/collections/slist.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/collections/strv.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/control_flow.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/convert.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/date.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/date_time.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/enums.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/error.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/exit_code.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/future_with_timeout.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gobject/binding.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gobject/binding_group.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gobject/flags.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gobject/mod.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gobject/signal_group.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gstring.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/gstring_builder.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/key_file.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/lib.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.

// Hack for the time being to retrieve the current function's name as a string.
// Based on the stdext cratelicensed under the MIT license.
//
// Copyright (c) 2020 Igor Aleksanov
//
// Previous attempts to get such a macro into std:
// * https://github.com/rust-lang/rfcs/pull/466
// * https://github.com/rust-lang/rfcs/pull/1719
// * https://github.com/rust-lang/rfcs/issues/1743
// * https://github.com/rust-lang/rfcs/pull/2818
// * ...
// rustdoc-stripper-ignore-next
/// This macro returns the name of the enclosing function.
/// As the internal implementation is based on the [`std::any::type_name`], this macro derives
/// all the limitations of this function.
///
/// ## Examples
///
/// ```rust
/// mod bar {
///     pub fn sample_function() {
///         assert!(glib::function_name!().ends_with("bar::sample_function"));
///     }
/// }
///
/// bar::sample_function();
/// ```
///
/// [`std::any::type_name`]: https://doc.rust-lang.org/std/any/fn.type_name.html
````

## `app/desktop/vendor/glib/src/log.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/main_context.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/main_context_channel.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/main_context_futures.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/manual_functions.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/object.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/param_spec.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/prelude.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/property.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/quark.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/shared.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/signal.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/source.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/source_futures.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/basic.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/boxed.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/interface.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/mod.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/object.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/object_impl_ref.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/shared.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/signal.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/subclass/types.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/thread_guard.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/thread_pool.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/time_span.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/time_zone.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/translate.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/types.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/unichar.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/unicollate.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/utils.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/value.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/value_array.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/variant.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/variant_dict.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/variant_iter.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/variant_type.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/win32.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/src/wrapper.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/desktop/vendor/glib/tests/check_gir.rs`

````text
// Take a look at the license at the top of the repository in the LICENSE file.
````

## `app/ios/gradlew`

````text
# Copyright © 2015 the original authors.

# Licensed under the Apache License, Version 2.0 (the "License");

# SPDX-License-Identifier: Apache-2.0
````

## `app/ios/gradlew.bat`

````text
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem
````

## `app/ios/native-core/src/analyzer/audio_analysis.rs`

````text
//! `native/analyzer` — Copyright (C) 2026 SFG545, Copyright (C) 2026 Kushagra
//! Singh. AGPLv3-or-later, kept as part of the same GPL-3 combined work.
````

## `app/ios/native-core/src/analyzer/mod.rs`

````text
//! `native/analyzer` — Copyright (C) 2026 SFG545, Copyright (C) 2026 Kushagra
//! Singh. The original is AGPLv3-or-later; this port keeps that status as
//! part of the same GPL-3 combined work.
````

## `app/ios/native-core/src/analyzer/tempo.rs`

````text
//! `native/analyzer` — Copyright (C) 2026 SFG545, Copyright (C) 2026 Kushagra
//! Singh. AGPLv3-or-later, kept as part of the same GPL-3 combined work.
````

## `app/ios/native-core/src/sound.rs`

````text
//! Lastwave Reference acoustic contour, derived from ec11a43 (GPL-3.0).
````

## `app/ios/native-core/vendor/soxr-0.1.3/CMakeLists.txt`

````text
# SoX Resampler Library       Copyright (c) 2007-18 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/cmake/Modules/FindCFlags.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/cmake/Modules/FindLibAVCodec.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-18 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/cmake/Modules/FindLibAVUtil.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-18 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/cmake/Modules/FindSIMD32.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/cmake/Modules/FindSIMD64.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/cmake/Modules/SetSystemProcessor.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/dist`

````text
# SoX Resampler Library       Copyright (c) 2007-18 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/1-single-block.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/1a-lsr.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/3-options-input-fn.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/4-split-channels.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/5-variable-rate.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/CMakeLists.txt`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/examples/examples-common.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/go`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/go.bat`

````text
rem SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
rem Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/inst-check`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/inst-check-soxr`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/CMakeLists.txt`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/calc_snr.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/callback_hang_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/callback_test.c`

````text
/*
** Copyright (C) 2003-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/cmake/Modules/FindFFTW.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/cmake/Modules/Findsndfile.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/config.h.in`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/downsample_test.c`

````text
/*
** Copyright (C) 2008-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/float_cast.h`

````text
/*
** Copyright (C) 2001-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU Lesser General Public License as published by
** the Free Software Foundation; either version 2.1 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU Lesser General Public License for more details.
**
** You should have received a copy of the GNU Lesser General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/float_short_test.c`

````text
/*
** Copyright (C) 2003-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/misc_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/multi_channel_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/multichan_throughput_test.c`

````text
/*
** Copyright (C) 2008-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/reset_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/simple_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/sndfile-resample.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/snr_bw_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/termination_test.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/throughput_test.c`

````text
/*
** Copyright (C) 2004-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/util.c`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/util.h`

````text
/*
** Copyright (C) 2002-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/lsr-tests/varispeed_test.c`

````text
/*
** Copyright (C) 2006-2011 Erik de Castro Lopo <erikd@mega-nerd.com>
**
** This program is free software; you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation; either version 2 of the License, or
** (at your option) any later version.
**
** This program is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with this program; if not, write to the Free Software
** Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/msvc/soxr-config.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/multi-arch`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/soxr-config.h.in`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/CMakeLists.txt`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/aliases.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/avfft32.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/avfft32s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/ccrw2.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr-core.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details.
 *
 * Constant-rate resampling engine-specific code. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details.
 *
 * Constant-rate resampling common code. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr32.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr32s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr64.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/cr64s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/data-io.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/data-io.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/dbesi0.c`

````text
/*  Copyright(C) 1996 Takuya OOURA

You may use, copy, modify this code for any purpose and
without fee.

Package home:  http://www.kurims.kyoto-u.ac.jp/~ooura/bessel.html
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/dev32s.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/dev64s.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fft4g.c`

````text
/* Copyright Takuya OOURA, 1996-2001.

You may use, copy, modify and distribute this code for any
purpose (include commercial use) and without fee.  Please
refer to this package when you modify this code.

Package home:  http://www.kurims.kyoto-u.ac.jp/~ooura/fft.html

Fast Fourier/Cosine/Sine Transform
    dimension   :one
    data length :power of 2
    decimation  :frequency
    radix       :4, 2
    data        :inplace
    table       :use
functions
    cdft: Complex Discrete Fourier Transform
    rdft: Real Discrete Fourier Transform
    ddct: Discrete Cosine Transform
    ddst: Discrete Sine Transform
    dfct: Cosine Transform of RDFT (Real Symmetric DFT)
    dfst: Sine Transform of RDFT (Real Anti-symmetric DFT)
function prototypes
    void cdft(int, int, double *, int *, double *);
    void rdft(int, int, double *, int *, double *);
    void ddct(int, int, double *, int *, double *);
    void ddst(int, int, double *, int *, double *);
    void dfct(int, double *, double *, int *, double *);
    void dfst(int, double *, double *, int *, double *);


-------- Complex DFT (Discrete Fourier Transform) --------
    [definition]
        <case1>
            X[k] = sum_j=0^n-1 x[j]*exp(2*pi*i*j*k/n), 0<=k<n
        <case2>
            X[k] = sum_j=0^n-1 x[j]*exp(-2*pi*i*j*k/n), 0<=k<n
        (notes: sum_j=0^n-1 is a summation from j=0 to n-1)
    [usage]
        <case1>
            ip[0] = 0; // first time only
            cdft(2*n, 1, a, ip, w);
        <case2>
            ip[0] = 0; // first time only
            cdft(2*n, -1, a, ip, w);
    [parameters]
        2*n            :data length (int)
                        n >= 1, n = power of 2
        a[0...2*n-1]   :input/output data (double *)
                        input data
                            a[2*j] = Re(x[j]),
                            a[2*j+1] = Im(x[j]), 0<=j<n
                        output data
                            a[2*k] = Re(X[k]),
                            a[2*k+1] = Im(X[k]), 0<=k<n
        ip[0...*]      :work area for bit reversal (int *)
                        length of ip >= 2+sqrt(n)
                        strictly,
                        length of ip >=
                            2+(1<<(int)(log(n+0.5)/log(2))/2).
                        ip[0],ip[1] are pointers of the cos/sin table.
        w[0...n/2-1]   :cos/sin table (double *)
                        w[],ip[] are initialized if ip[0] == 0.
    [remark]
        Inverse of
            cdft(2*n, -1, a, ip, w);
        is
            cdft(2*n, 1, a, ip, w);
            for (j = 0; j <= 2 * n - 1; j++) {
                a[j] *= 1.0 / n;
            }
        .


-------- Real DFT / Inverse of Real DFT --------
    [definition]
        <case1> RDFT
            R[k] = sum_j=0^n-1 a[j]*cos(2*pi*j*k/n), 0<=k<=n/2
            I[k] = sum_j=0^n-1 a[j]*sin(2*pi*j*k/n), 0<k<n/2
        <case2> IRDFT (excluding scale)
            a[k] = (R[0] + R[n/2]*cos(pi*k))/2 +
                   sum_j=1^n/2-1 R[j]*cos(2*pi*j*k/n) +
                   sum_j=1^n/2-1 I[j]*sin(2*pi*j*k/n), 0<=k<n
    [usage]
        <case1>
            ip[0] = 0; // first time only
            rdft(n, 1, a, ip, w);
        <case2>
            ip[0] = 0; // first time only
            rdft(n, -1, a, ip, w);
    [parameters]
        n              :data length (int)
                        n >= 2, n = power of 2
        a[0...n-1]     :input/output data (double *)
                        <case1>
                            output data
                                a[2*k] = R[k], 0<=k<n/2
                                a[2*k+1] = I[k], 0<k<n/2
                                a[1] = R[n/2]
                        <case2>
                            input data
                                a[2*j] = R[j], 0<=j<n/2
                                a[2*j+1] = I[j], 0<j<n/2
                                a[1] = R[n/2]
        ip[0...*]      :work area for bit reversal (int *)
                        length of ip >= 2+sqrt(n/2)
                        strictly,
                        length of ip >=
                            2+(1<<(int)(log(n/2+0.5)/log(2))/2).
                        ip[0],ip[1] are pointers of the cos/sin table.
        w[0...n/2-1]   :cos/sin table (double *)
                        w[],ip[] are initialized if ip[0] == 0.
    [remark]
        Inverse of
            rdft(n, 1, a, ip, w);
        is
            rdft(n, -1, a, ip, w);
            for (j = 0; j <= n - 1; j++) {
                a[j] *= 2.0 / n;
            }
        .


-------- DCT (Discrete Cosine Transform) / Inverse of DCT --------
    [definition]
        <case1> IDCT (excluding scale)
            C[k] = sum_j=0^n-1 a[j]*cos(pi*j*(k+1/2)/n), 0<=k<n
        <case2> DCT
            C[k] = sum_j=0^n-1 a[j]*cos(pi*(j+1/2)*k/n), 0<=k<n
    [usage]
        <case1>
            ip[0] = 0; // first time only
            ddct(n, 1, a, ip, w);
        <case2>
            ip[0] = 0; // first time only
            ddct(n, -1, a, ip, w);
    [parameters]
        n              :data length (int)
                        n >= 2, n = power of 2
        a[0...n-1]     :input/output data (double *)
                        output data
                            a[k] = C[k], 0<=k<n
        ip[0...*]      :work area for bit reversal (int *)
                        length of ip >= 2+sqrt(n/2)
                        strictly,
                        length of ip >=
                            2+(1<<(int)(log(n/2+0.5)/log(2))/2).
                        ip[0],ip[1] are pointers of the cos/sin table.
        w[0...n*5/4-1] :cos/sin table (double *)
                        w[],ip[] are initialized if ip[0] == 0.
    [remark]
        Inverse of
            ddct(n, -1, a, ip, w);
        is
            a[0] *= 0.5;
            ddct(n, 1, a, ip, w);
            for (j = 0; j <= n - 1; j++) {
                a[j] *= 2.0 / n;
            }
        .


-------- DST (Discrete Sine Transform) / Inverse of DST --------
    [definition]
        <case1> IDST (excluding scale)
            S[k] = sum_j=1^n A[j]*sin(pi*j*(k+1/2)/n), 0<=k<n
        <case2> DST
            S[k] = sum_j=0^n-1 a[j]*sin(pi*(j+1/2)*k/n), 0<k<=n
    [usage]
        <case1>
            ip[0] = 0; // first time only
            ddst(n, 1, a, ip, w);
        <case2>
            ip[0] = 0; // first time only
            ddst(n, -1, a, ip, w);
    [parameters]
        n              :data length (int)
                        n >= 2, n = power of 2
        a[0...n-1]     :input/output data (double *)
                        <case1>
                            input data
                                a[j] = A[j], 0<j<n
                                a[0] = A[n]
                            output data
                                a[k] = S[k], 0<=k<n
                        <case2>
                            output data
                                a[k] = S[k], 0<k<n
                                a[0] = S[n]
        ip[0...*]      :work area for bit reversal (int *)
                        length of ip >= 2+sqrt(n/2)
                        strictly,
                        length of ip >=
                            2+(1<<(int)(log(n/2+0.5)/log(2))/2).
                        ip[0],ip[1] are pointers of the cos/sin table.
        w[0...n*5/4-1] :cos/sin table (double *)
                        w[],ip[] are initialized if ip[0] == 0.
    [remark]
        Inverse of
            ddst(n, -1, a, ip, w);
        is
            a[0] *= 0.5;
            ddst(n, 1, a, ip, w);
            for (j = 0; j <= n - 1; j++) {
                a[j] *= 2.0 / n;
            }
        .


-------- Cosine Transform of RDFT (Real Symmetric DFT) --------
    [definition]
        C[k] = sum_j=0^n a[j]*cos(pi*j*k/n), 0<=k<=n
    [usage]
        ip[0] = 0; // first time only
        dfct(n, a, t, ip, w);
    [parameters]
        n              :data length - 1 (int)
                        n >= 2, n = power of 2
        a[0...n]       :input/output data (double *)
                        output data
                            a[k] = C[k], 0<=k<=n
        t[0...n/2]     :work area (double *)
        ip[0...*]      :work area for bit reversal (int *)
                        length of ip >= 2+sqrt(n/4)
                        strictly,
                        length of ip >=
                            2+(1<<(int)(log(n/4+0.5)/log(2))/2).
                        ip[0],ip[1] are pointers of the cos/sin table.
        w[0...n*5/8-1] :cos/sin table (double *)
                        w[],ip[] are initialized if ip[0] == 0.
    [remark]
        Inverse of
            a[0] *= 0.5;
            a[n] *= 0.5;
            dfct(n, a, t, ip, w);
        is
            a[0] *= 0.5;
            a[n] *= 0.5;
            dfct(n, a, t, ip, w);
            for (j = 0; j <= n; j++) {
                a[j] *= 2.0 / n;
            }
        .


-------- Sine Transform of RDFT (Real Anti-symmetric DFT) --------
    [definition]
        S[k] = sum_j=1^n-1 a[j]*sin(pi*j*k/n), 0<k<n
    [usage]
        ip[0] = 0; // first time only
        dfst(n, a, t, ip, w);
    [parameters]
        n              :data length + 1 (int)
                        n >= 2, n = power of 2
        a[0...n-1]     :input/output data (double *)
                        output data
                            a[k] = S[k], 0<k<n
                        (a[0] is used for work area)
        t[0...n/2-1]   :work area (double *)
        ip[0...*]      :work area for bit reversal (int *)
                        length of ip >= 2+sqrt(n/4)
                        strictly,
                        length of ip >=
                            2+(1<<(int)(log(n/4+0.5)/log(2))/2).
                        ip[0],ip[1] are pointers of the cos/sin table.
        w[0...n*5/8-1] :cos/sin table (double *)
                        w[],ip[] are initialized if ip[0] == 0.
    [remark]
        Inverse of
            dfst(n, a, t, ip, w);
        is
            dfst(n, a, t, ip, w);
            for (j = 1; j <= n - 1; j++) {
                a[j] *= 2.0 / n;
            }
        .


Appendix :
    The cos/sin table is recalculated when the larger table required.
    w[] and ip[] are compatible with all routines.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fft4g.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fft4g32.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fft4g32s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fft4g64.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fft4g_cache.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/fifo.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/filter.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/filter.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/half-coefs.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/half-fir.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/internal.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/math-wrap.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft-avx.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft-wrap.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft.c`

````text
/* Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )

   Based on original fortran 77 code from FFTPACKv4 from NETLIB
   (http://www.netlib.org/fftpack), authored by Dr Paul Swarztrauber
   of NCAR, in 1985.

   As confirmed by the NCAR fftpack software curators, the following
   FFTPACKv5 license applies to FFTPACKv4 sources. My changes are
   released under the same terms.

   FFTPACK license:

   http://www.cisl.ucar.edu/css/software/fftpack5/ftpk.html

   Copyright (c) 2004 the University Corporation for Atmospheric
   Research ("UCAR"). All rights reserved. Developed by NCAR's
   Computational and Information Systems Laboratory, UCAR,
   www.cisl.ucar.edu.

   Redistribution and use of the Software in source and binary forms,
   with or without modification, is permitted provided that the
   following conditions are met:

   - Neither the names of NCAR's Computational and Information Systems
   Laboratory, the University Corporation for Atmospheric Research,
   nor the names of its sponsors or contributors may be used to
   endorse or promote products derived from this Software without
   specific prior written permission.

   - Redistributions of source code must retain the above copyright
   notices, this list of conditions, and the disclaimer below.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions, and the disclaimer below in the
   documentation and/or other materials provided with the
   distribution.

   THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
   EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE WARRANTIES OF
   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
   NONINFRINGEMENT. IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT
   HOLDERS BE LIABLE FOR ANY CLAIM, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES OR OTHER LIABILITY, WHETHER IN AN
   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
   SOFTWARE.


   PFFFT : a Pretty Fast FFT.

   This file is largerly based on the original FFTPACK implementation, modified in
   order to take advantage of SIMD instructions of modern CPUs.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft.h`

````text
/* Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )

   Based on original fortran 77 code from FFTPACKv4 from NETLIB,
   authored by Dr Paul Swarztrauber of NCAR, in 1985.

   As confirmed by the NCAR fftpack software curators, the following
   FFTPACKv5 license applies to FFTPACKv4 sources. My changes are
   released under the same terms.

   FFTPACK license:

   http://www.cisl.ucar.edu/css/software/fftpack5/ftpk.html

   Copyright (c) 2004 the University Corporation for Atmospheric
   Research ("UCAR"). All rights reserved. Developed by NCAR's
   Computational and Information Systems Laboratory, UCAR,
   www.cisl.ucar.edu.

   Redistribution and use of the Software in source and binary forms,
   with or without modification, is permitted provided that the
   following conditions are met:

   - Neither the names of NCAR's Computational and Information Systems
   Laboratory, the University Corporation for Atmospheric Research,
   nor the names of its sponsors or contributors may be used to
   endorse or promote products derived from this Software without
   specific prior written permission.

   - Redistributions of source code must retain the above copyright
   notices, this list of conditions, and the disclaimer below.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions, and the disclaimer below in the
   documentation and/or other materials provided with the
   distribution.

   THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
   EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE WARRANTIES OF
   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
   NONINFRINGEMENT. IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT
   HOLDERS BE LIABLE FOR ANY CLAIM, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES OR OTHER LIABILITY, WHETHER IN AN
   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
   SOFTWARE.
*/
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft32.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft32s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/pffft64s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/poly-fir.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/poly-fir0.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/rdft.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/rdft_t.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/rint-clip.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/rint.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/soxr-lsr.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/soxr-lsr.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/soxr.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/soxr.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-18 robs@users.sourceforge.net
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/std-types.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/util-simd.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/util32s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/util32s.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/util64s.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/util64s.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/vr-coefs.c`

````text
/* SoX Resampler Library         Copyright (c) 2013 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/vr-coefs.h`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/src/vr32.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/1-delay-clear.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-15 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/CMakeLists.txt`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/bandwidth-test`

````text
# SoX Resampler Library       Copyright (c) 2007-15 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/cmp-test.cmake`

````text
# SoX Resampler Library       Copyright (c) 2007-13 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/eg-test`

````text
# SoX Resampler Library       Copyright (c) 2007-15 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/io-test`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/large-ratio-test`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/phase-test`

````text
# SoX Resampler Library       Copyright (c) 2007-15 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/q-test`

````text
# SoX Resampler Library       Copyright (c) 2007-15 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/scripts`

````text
# SoX Resampler Library       Copyright (c) 2007-15 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/throughput-test`

````text
# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/throughput-test.bat`

````text
rem SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
rem Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/throughput.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/time-test`

````text
# SoX Resampler Library       Copyright (c) 2007-15 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/vector-cmp.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/ios/native-core/vendor/soxr-0.1.3/tests/vector-gen.c`

````text
/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */
````

## `app/web/app/src/playback/automix/analysis/analysis.worker.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/analyzer.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/beatgrid.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/camelot.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/energy.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/essentia.d.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/models.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `app/web/app/src/playback/automix/analysis/selftest.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/testtones.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/analysis/types.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/db.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/autodj.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/deck.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/eq.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/grid.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/loopDeck.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/selector.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/stretch-types.d.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/timeline.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/automix/engine/transition-scheduler.ts`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `app/web/app/src/playback/youtube/ShakaPlayerAdapter.ts`

````text
// SPDX-License-Identifier: MIT
````

## `app/web/app/src/playback/youtube/shakaHelpers.ts`

````text
// SPDX-License-Identifier: MIT
````

## `core/apple/resources/Resources/YouTubeSolver/yt.solver.core.js`

````text
/*!
 * SPDX-License-Identifier: Unlicense
 * This file was automatically generated by https://github.com/yt-dlp/ejs
 */
````

## `core/apple/resources/Resources/YouTubeSolver/yt.solver.lib.js`

````text
/*!
 * SPDX-License-Identifier: Unlicense
 * This file was automatically generated by https://github.com/yt-dlp/ejs
 *
 * Bundled Dependencies:
 *
 * ---
 * Name: meriyah
 * Version: 6.1.4
 * License: ISC
 * Repository: git+https://github.com/meriyah/meriyah.git
 * Author: Kenny F. (https://github.com/KFlash)
 *
 * ISC License
 *
 * Copyright (c) 2019 and later, KFlash and others.
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 *
 * ---
 *
 * ---
 * Name: astring
 * Version: 1.9.0
 * License: MIT
 * Repository: https://github.com/davidbonnet/astring.git
 * Author: David Bonnet <david@bonnet.cc>
 *
 * Copyright (c) 2015, David Bonnet <david@bonnet.cc>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ---
 */
````

## `core/native/analyzer/audio_analysis.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/native/analyzer/audio_analysis.h`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/native/analyzer/mel_spectrogram.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/native/analyzer/mel_spectrogram.h`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/native/analyzer/resampler.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/native/analyzer/resampler.h`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
// Sample-rate conversion for the DSP analyzer's front end.
//
// The analyzer accepts exactly kAnalysisSampleRate (see audio_analysis.h /
// TrackFeatures.kt) and refuses anything else, so something has to convert;
// streams arrive at 44.1 or 48 kHz. Doing it by dropping samples would fold
// everything above the analyzer's Nyquist back down into the band it reads,
// and an aliased spectrum produces a wrong tempo/key/structure reading rather
// than a noisy one -- the transition planner would then take that reading at
// face value. So this is a windowed-sinc resampler rather than decimation:
// the anti-alias filter is the point of it.
//
// Calls borrow the input only until they return, own the returned storage,
// and are reentrant. The work is O(taps * output) and allocates, so it
// belongs on a worker thread alongside the rest of analysis.
````

## `core/native/analyzer/tempo_analysis.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
// Offline tempo and beat-grid estimation, entirely DSP (autocorrelation plus a
// phase-locked tracking loop) -- no ML model. All spectra, envelopes, scores,
// and event vectors are call-owned allocations; this file has no
// synchronization or real-time guarantees and must run on a worker thread.
//
// This is the confidence a transition policy without a trained beat-tracking
// model has to work with. It is deliberately treated as less trustworthy than
// a model's grid would be -- see MIN_BEATMATCH_CONFIDENCE in
// TransitionPolicy.kt -- but is real evidence, not a guess: BuildStructure and
// the mix-in/out scoring in audio_analysis.cpp both read the resulting
// downbeats to place transitions on the bar.
````

## `core/native/analyzer/vocal_spectrogram.cpp`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/native/analyzer/vocal_spectrogram.h`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later

// Linear-frequency STFT magnitude front end for the open-unmix vocal
// separation model.
//
// Every constant here is dictated by the trained network, not chosen: the
// model was trained on exactly this STFT (torch.stft with these settings,
// see openunmix/transforms.py's TorchSTFT, MIT-licensed, Inria/SigSep), and
// a front end that differs in window convention or normalization feeds it
// something it has never seen -- the same lesson the Beat This mel front end
// already encodes, see mel_spectrogram.h.
//
// `channels` is planar (non-interleaved) stereo Float32 PCM that must
// already be at kVocalSpectrogramSampleRate; the caller owns resampling and
// mono duplication. Calls borrow the input only until they return, own all
// returned storage, and are reentrant. The work is O(n log n) and allocates,
// so it belongs on a worker thread.
````

## `core/native/ffmpeg/build.sh`

````text
# Builds app/src/main/jniLibs/<abi>/libffmpegJNI.so: Media3's FFmpeg audio
# decoder JNI (ffmpeg_jni.cc, beside this script) statically linked against
# libavcodec + libavutil from librempeg.
# Licence: built with --enable-gpl --enable-version3 and without --enable-agpl
# (in librempeg only avfilter's frame threading and the ffmpeg CLI are AGPL,
# and neither is built), so the result is GPLv3, like the app. libavfilter is
# built — librempeg's libavcodec will not configure without it — but with
# --disable-everything it carries no filters.
````

## `core/native/ffmpeg/ffmpeg_jni.cc`

````text
// SPDX-License-Identifier: Apache-2.0
````

## `core/native/loopmix/loop_mix_renderer.cpp`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
// nuvx: the Automix loop mix's renderer. See loop_mix_renderer.h.
````

## `core/native/loopmix/loop_mix_renderer.h`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
// nuvx: the Automix loop mix's renderer. Two decks, each a decoded region
// of one song played through Signalsmith Stretch, looped on whole bars and
// following a tempo ramp beat by beat, mixed into one stereo stream.
//
// Everything is on one clock: mix frame 0 is the moment the outgoing song's
// loop starts (t0). Each deck follows "knots" -- (time since t0, track
// position) for every beat of the mix -- so its tempo changes on the beat and
// its loop seams land on the exact sample. The renderer runs inside the
// outgoing player's audio sink, so the start of the mix is sample-exact
// against the song it takes over from, and the two decks can't drift apart.
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/BassHandoffPlanner.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/IncomingCuePlanner.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/LoopMix.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-only
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/TrackAnalysis.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/TransitionModels.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/TransitionPairCompatibility.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/TransitionPlanner.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/commonMain/kotlin/com/bxane/nuvx/playback/smart/TransitionPolicy.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/YtMusicHistoryRepository.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/YtMusicHomeRepository.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/YtMusicPlaylistRepository.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/YtMusicSearchRepository.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/YtMusicVersionRepository.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/InnertubeAccountParser.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/InnertubeBrowseItemParser.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/InnertubeHomeParser.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/InnertubeLibraryStateParser.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/InnertubePlaylistShelfParser.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/innertube/InnertubeSearchParser.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/data/radio/RadioBrowser.kt`

````text
// SPDX-License-Identifier: GPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/playback/smart/BeatTracker.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/playback/smart/MelSpectrogram.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/playback/smart/TrackFeatures.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/bxane/nuvx/playback/smart/VocalTracker.kt`

````text
// SPDX-License-Identifier: AGPL-3.0-or-later
````

## `core/shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/HeartBeat.kt`

````text
// SPDX-License-Identifier: GPL-3.0-only
````

## `core/shared/src/jvmSharedMain/kotlin/com/my/kizzy/gateway/entities/Ready.kt`

````text
// SPDX-License-Identifier: GPL-3.0-only
````

## `core/shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/KizzyRPC.kt`

````text
// SPDX-License-Identifier: GPL-3.0-only
````

## `core/shared/src/jvmSharedMain/kotlin/com/my/kizzy/rpc/RpcImage.kt`

````text
// SPDX-License-Identifier: GPL-3.0-only
````

## `gradlew`

````text
#
# Copyright © 2015 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#
````

## `gradlew.bat`

````text
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem
````
