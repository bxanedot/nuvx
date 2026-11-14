// SPDX-License-Identifier: AGPL-3.0-or-later

#include <jni.h>

#include <vector>

#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeResample(
    JNIEnv* env,
    jclass,
    jfloatArray samples,
    jdouble input_rate,
    jdouble output_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const std::vector<float> resampled =
      nuvx::smart::Resample(input, input_rate, output_rate);

  const jsize produced = static_cast<jsize>(resampled.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, resampled.data());
  }
  return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass,
    jfloatArray samples,
    jdouble sample_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const nuvx::smart::BeatSpectrogram spectrogram =
      nuvx::smart::ComputeBeatSpectrogram(input, sample_rate);

  const jsize produced = static_cast<jsize>(spectrogram.values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, spectrogram.values.data());
  }
  return result;
}

JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeMelCount(
    JNIEnv*,
    jclass) {
  return static_cast<jint>(nuvx::smart::kBeatSpectrogramMels);
}

JNIEXPORT jdouble JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeSampleRate(
    JNIEnv*,
    jclass) {
  return nuvx::smart::kBeatSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeHop(
    JNIEnv*,
    jclass) {
  return static_cast<jint>(nuvx::smart::kBeatSpectrogramHop);
}

}
