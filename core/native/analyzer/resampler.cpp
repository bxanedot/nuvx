// SPDX-License-Identifier: AGPL-3.0-or-later
#include "resampler.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace nuvx::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

constexpr double kKernelResolution = 512.0;

double Sinc(double x) {
  if (std::abs(x) < 1e-12) return 1.0;
  const double scaled = kPi * x;
  return std::sin(scaled) / scaled;
}

double Blackman(double position) {
  return 0.42 - 0.5 * std::cos(2.0 * kPi * position) +
         0.08 * std::cos(4.0 * kPi * position);
}

}

namespace {

double KernelAt(double offset, double cutoff, double half_width) {
  offset = std::abs(offset);
  if (offset >= half_width) return 0.0;
  const double window = Blackman((offset + half_width) / (2.0 * half_width));
  return 2.0 * cutoff * Sinc(2.0 * cutoff * offset) * window;
}

constexpr long kMaxPhases = 1024;

bool WholeRate(double rate, long* whole) {
  const double rounded = std::round(rate);
  if (rounded < 1.0 || std::abs(rate - rounded) > 1e-9) return false;
  *whole = static_cast<long>(rounded);
  return true;
}

long Gcd(long a, long b) {
  while (b != 0) {
    const long next = a % b;
    a = b;
    b = next;
  }
  return a;
}

bool ResamplePolyphase(
  const std::vector<float>& input,
  long input_rate,
  long output_rate,
  std::vector<float>* out
) {
  const long divisor = Gcd(input_rate, output_rate);
  const long up = output_rate / divisor;
  const long down = input_rate / divisor;
  if (up > kMaxPhases) return false;

  const double ratio = static_cast<double>(up) / static_cast<double>(down);
  const double cutoff = 0.5 * std::min(1.0, ratio);
  const double half_width = static_cast<double>(kResamplerZeroCrossings) / (2.0 * cutoff);

  const size_t output_count =
    static_cast<size_t>(std::floor(static_cast<double>(input.size()) * ratio));
  if (output_count == 0) {
    out->clear();
    return true;
  }

  const long reach = static_cast<long>(std::ceil(half_width)) + 1;
  const long taps = 2 * reach + 1;
  std::vector<float> weights(static_cast<size_t>(up * taps), 0.0f);
  for (long phase = 0; phase < up; ++phase) {
    const double fraction = static_cast<double>(phase) / static_cast<double>(up);
    double sum = 0.0;
    std::vector<double> row(static_cast<size_t>(taps));
    for (long k = 0; k < taps; ++k) {
      row[k] = KernelAt(fraction - static_cast<double>(k - reach), cutoff, half_width);
      sum += row[k];
    }
    float* destination = weights.data() + phase * taps;
    for (long k = 0; k < taps; ++k) {
      destination[k] = static_cast<float>(sum != 0.0 ? row[k] / sum : 0.0);
    }
  }

  out->assign(output_count, 0.0f);
  const long size = static_cast<long>(input.size());
  const float* samples = input.data();
  for (size_t index = 0; index < output_count; ++index) {
    const int64_t position = static_cast<int64_t>(index) * down;
    const long whole = static_cast<long>(position / up);
    const long phase = static_cast<long>(position % up);
    const float* row = weights.data() + phase * taps;
    const long first = whole - reach;

    if (first >= 0 && first + taps <= size) {
      const float* run = samples + first;
      float sum = 0.0f;
      {
#pragma clang fp reassociate(on)
        for (long k = 0; k < taps; ++k) sum += row[k] * run[k];
      }
      (*out)[index] = sum;
    } else {
      double sum = 0.0;
      double weight_sum = 0.0;
      for (long k = 0; k < taps; ++k) {
        const long tap = std::min(std::max(first + k, 0L), size - 1);
        sum += static_cast<double>(row[k]) * static_cast<double>(samples[tap]);
        weight_sum += row[k];
      }
      (*out)[index] = static_cast<float>(weight_sum != 0.0 ? sum / weight_sum : 0.0);
    }
  }
  return true;
}

}

std::vector<float> Resample(
  const std::vector<float>& input,
  double input_rate,
  double output_rate
) {
  if (input.empty() || input_rate <= 0.0 || output_rate <= 0.0) return {};
  if (std::abs(input_rate - output_rate) < 1e-6) return input;

  long whole_input = 0;
  long whole_output = 0;
  if (WholeRate(input_rate, &whole_input) && WholeRate(output_rate, &whole_output)) {
    std::vector<float> output;
    if (ResamplePolyphase(input, whole_input, whole_output, &output)) return output;
  }

  const double ratio = output_rate / input_rate;
  const double cutoff = 0.5 * std::min(1.0, ratio);
  const double half_width = static_cast<double>(kResamplerZeroCrossings) / (2.0 * cutoff);

  const size_t output_count =
    static_cast<size_t>(std::floor(static_cast<double>(input.size()) * ratio));
  if (output_count == 0) return {};

  const size_t table_size =
    static_cast<size_t>(std::ceil(half_width * kKernelResolution)) + 2;
  std::vector<double> kernel(table_size);
  for (size_t index = 0; index < table_size; ++index) {
    const double offset = static_cast<double>(index) / kKernelResolution;
    const double window = Blackman((offset + half_width) / (2.0 * half_width));
    kernel[index] = 2.0 * cutoff * Sinc(2.0 * cutoff * offset) * window;
  }

  std::vector<float> output(output_count, 0.0f);
  const auto last = static_cast<long>(input.size()) - 1;

  for (size_t index = 0; index < output_count; ++index) {
    const double centre = static_cast<double>(index) / ratio;
    const long first_tap = static_cast<long>(std::ceil(centre - half_width));
    const long last_tap = static_cast<long>(std::floor(centre + half_width));

    double sum = 0.0;
    double weight_sum = 0.0;
    for (long tap = first_tap; tap <= last_tap; ++tap) {
      const double offset = std::abs(centre - static_cast<double>(tap));
      const double scaled = offset * kKernelResolution;
      const auto slot = static_cast<size_t>(scaled);
      if (slot + 1 >= table_size) continue;
      const double fraction = scaled - static_cast<double>(slot);
      const double weight =
        kernel[slot] + fraction * (kernel[slot + 1] - kernel[slot]);

      const long clamped = std::min(std::max(tap, 0L), last);
      sum += weight * static_cast<double>(input[static_cast<size_t>(clamped)]);
      weight_sum += weight;
    }

    output[index] = static_cast<float>(weight_sum != 0.0 ? sum / weight_sum : 0.0);
  }

  return output;
}

}
