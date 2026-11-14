// SPDX-License-Identifier: AGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <vector>

namespace nuvx::smart {

inline constexpr size_t kResamplerZeroCrossings = 32;

std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
);

}
