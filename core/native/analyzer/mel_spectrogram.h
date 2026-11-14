// SPDX-License-Identifier: AGPL-3.0-or-later
/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native mel
 * front end this file is adapted from almost unchanged: every constant here
 * is dictated by the Beat This! model's training data, not a preference, so
 * reimplementing it from a description would silently retrain the input
 * distribution the network has never seen.
 */


#pragma once

#include <cstddef>
#include <vector>

namespace nuvx::smart {

inline constexpr double kBeatSpectrogramSampleRate = 22050;
inline constexpr size_t kBeatSpectrogramMels = 128;
inline constexpr size_t kBeatSpectrogramFft = 1024;
inline constexpr size_t kBeatSpectrogramHop = 441;

struct BeatSpectrogram {
  std::vector<float> values;
  size_t frames = 0;
};

BeatSpectrogram ComputeBeatSpectrogram(
  const std::vector<float>& samples,
  double sample_rate
);

}
