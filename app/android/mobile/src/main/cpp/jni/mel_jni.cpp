







#include <jni.h>

#include <vector>

#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"

extern "C" {

// Converts mono float PCM to the model's rate. Separate from nativeCompute
// because the caller decodes at whatever rate the container carries and only
// then knows what conversion is needed.
JNIEXPORT jfloatArray JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeResample(
    JNIEnv* env,
    jclass /* clazz */,
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

// Returns the flattened [frames][kBeatSpectrogramMels] spectrogram, or an
// empty array when the front end declined the input (wrong rate, or shorter
// than one padded frame). The caller derives the frame count by dividing, so
// no second return value is needed.
JNIEXPORT jfloatArray JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
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
    return nullptr;  // OOM; the exception is already pending.
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, spectrogram.values.data());
  }
  return result;
}

// The mel band count is part of the model contract rather than a choice, so
// it is read from the header instead of being duplicated in Kotlin.
JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeMelCount(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return static_cast<jint>(nuvx::smart::kBeatSpectrogramMels);
}

JNIEXPORT jdouble JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeSampleRate(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return nuvx::smart::kBeatSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_MelSpectrogram_nativeHop(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return static_cast<jint>(nuvx::smart::kBeatSpectrogramHop);
}

}  // extern "C"
