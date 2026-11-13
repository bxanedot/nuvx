// SPDX-License-Identifier: Apache-2.0
/*
 * Vendored by nuvx from androidx/media 1.11.0
 * (libraries/decoder_ffmpeg); there is no published artifact of the FFmpeg
 * extension, and the native half is built from librempeg by
 * native/ffmpeg/build.sh.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when an FFmpeg decoder error occurs. */
@UnstableApi
public final class FfmpegDecoderException extends DecoderException {

  /* package */ FfmpegDecoderException(String message) {
    super(message);
  }

  /* package */ FfmpegDecoderException(String message, Throwable cause) {
    super(message, cause);
  }
}
