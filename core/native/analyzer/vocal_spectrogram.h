// SPDX-License-Identifier: AGPL-3.0-or-later
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
  std::vector<float> values;
  size_t frames = 0;
};

VocalSpectrogram ComputeVocalSpectrogram(
  const std::vector<std::vector<float>>& channels,
  double sample_rate
);

}
