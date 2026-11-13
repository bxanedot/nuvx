







#include <jni.h>

#include <vector>

#include "analyzer/vocal_spectrogram.h"

extern "C" {

// Takes the two channels as separate arrays rather than one interleaved one,
// mirroring the planar layout the front end wants and avoiding a deinterleave
// on either side of the boundary.
JNIEXPORT jfloatArray JNICALL
Java_com_bxane_nuvx_playback_smart_VocalSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
    jfloatArray right,
    jdouble sample_rate) {
  const jsize left_count = env->GetArrayLength(left);
  const jsize right_count = env->GetArrayLength(right);

  std::vector<std::vector<float>> channels(2);
  channels[0].resize(static_cast<size_t>(left_count));
  channels[1].resize(static_cast<size_t>(right_count));
  if (left_count > 0) env->GetFloatArrayRegion(left, 0, left_count, channels[0].data());
  if (right_count > 0) env->GetFloatArrayRegion(right, 0, right_count, channels[1].data());

  const nuvx::smart::VocalSpectrogram spectrogram =
      nuvx::smart::ComputeVocalSpectrogram(channels, sample_rate);

  const jsize produced = static_cast<jsize>(spectrogram.values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, spectrogram.values.data());
  }
  return result;
}

JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_VocalSpectrogram_nativeBins(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(nuvx::smart::kVocalSpectrogramBins);
}

JNIEXPORT jdouble JNICALL
Java_com_bxane_nuvx_playback_smart_VocalSpectrogram_nativeSampleRate(
    JNIEnv* /* env */, jclass /* clazz */) {
  return nuvx::smart::kVocalSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_VocalSpectrogram_nativeHop(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(nuvx::smart::kVocalSpectrogramHop);
}

JNIEXPORT jint JNICALL
Java_com_bxane_nuvx_playback_smart_VocalSpectrogram_nativeFftSize(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(nuvx::smart::kVocalSpectrogramFft);
}

}  // extern "C"
