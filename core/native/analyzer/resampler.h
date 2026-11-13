















#pragma once

#include <cstddef>
#include <vector>

namespace nuvx::smart {

// Zero crossings kept either side of each output sample. Higher is a better
// stopband at linear cost; 32 puts the aliasing well below the noise floor of
// anything that reaches this code as lossy audio.
inline constexpr size_t kResamplerZeroCrossings = 32;

/**
 * Resamples contiguous mono float PCM from `input_rate` to `output_rate`.
 *
 * Returns the input unchanged when the rates already match, and an empty
 * vector when either rate is not positive or the input is empty. There is no
 * error channel: callers treat empty as "no analysis available", which is
 * what every other stage of this pipeline does.
 */
std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
);

}  // namespace nuvx::smart
