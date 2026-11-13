
/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native STFT
 * front end this file is adapted from almost unchanged: every constant here
 * is dictated by the open-unmix model's training data, not a preference, so
 * reimplementing it from a description would feed the network an input
 * distribution it has never seen.
 */

















#pragma once

#include <cstddef>
#include <vector>

namespace nuvx::smart {

inline constexpr double kVocalSpectrogramSampleRate = 44100;
inline constexpr size_t kVocalSpectrogramChannels = 2;
inline constexpr size_t kVocalSpectrogramFft = 4096;
inline constexpr size_t kVocalSpectrogramBins = kVocalSpectrogramFft / 2 + 1;
inline constexpr size_t kVocalSpectrogramHop = 1024;

struct VocalSpectrogram {
  // Row-major [channel][bin][frame], flattened: channel c, bin b, frame f is
  // at index (c * kVocalSpectrogramBins + b) * frames + f. This is bin-major
  // rather than the more natural frame-major order an STFT computes one frame
  // at a time in, specifically so it matches the ONNX model's expected tensor
  // shape [1, 2, kVocalSpectrogramBins, frames] exactly -- the caller hands
  // this straight to an inference call with no transpose.
  std::vector<float> values;
  size_t frames = 0;
};

/**
 * Computes the linear-magnitude STFT the vocal-separation model expects.
 *
 * Returns an empty result -- not an error -- when the sample rate is not
 * kVocalSpectrogramSampleRate, the channel count is not
 * kVocalSpectrogramChannels, or the input is shorter than one padded frame.
 * Callers treat that as "no mask available" and skip the vocal duck.
 */
VocalSpectrogram ComputeVocalSpectrogram(
  const std::vector<std::vector<float>>& channels,
  double sample_rate
);

}  // namespace nuvx::smart
