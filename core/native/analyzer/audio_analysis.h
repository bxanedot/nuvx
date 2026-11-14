// SPDX-License-Identifier: AGPL-3.0-or-later
/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native
 * analyzer this file is adapted from almost unchanged: the mix-out budget,
 * phrase detection and cue scoring below were tuned against real material,
 * and reimplementing them from a description would produce different numbers
 * that nuvx's transition policy is not calibrated for.
 */


#pragma once

#include <string>
#include <vector>

namespace nuvx::smart {

struct EnergyPoint {
  double time = 0;
  double energy = 0;
};

struct Phrase {
  double start = 0;
  double end = 0;
  std::string type;
  double confidence = 0;
};

struct TempoResult {
  double bpm = 0;
  double beat_interval = 0;
  double first_beat = 0;
  double confidence = 0;
  std::vector<double> beats;
  std::vector<double> downbeats;
};

struct MixCuePoint {
  double time = 0;
  double score = 0;
  std::string type;
};

struct AnalysisResult {
  double duration = 0;
  double bpm = 0;
  double beat_interval = 0;
  double first_beat = 0;
  double beat_confidence = 0;
  std::vector<double> beats;
  std::vector<double> downbeats;
  std::vector<double> phrase_boundaries;
  std::vector<Phrase> phrases;
  std::string key;
  double key_confidence = 0;
  std::vector<double> chroma;
  double audible_start_time = 0;
  double pickup_time = 0;
  double pickup_confidence = 0;
  double mix_in_time = 0;
  double mix_in_confidence = 0;
  double intro_end_time = 0;
  double outro_start_time = 0;
  double content_end_time = 0;
  double mix_out_time = 0;
  double loudness_lufs = -70;
  double peak_dbfs = -70;
  double dynamic_range_db = 0;
  std::vector<EnergyPoint> energy_curve;
  std::vector<EnergyPoint> low_energy_curve;
  std::vector<EnergyPoint> mid_energy_curve;
  std::vector<EnergyPoint> high_energy_curve;
  std::vector<double> vocal_activity_mask;
  std::vector<MixCuePoint> mix_in_candidates;
  std::vector<MixCuePoint> mix_out_candidates;
  double vocal_probability = 0;
};

AnalysisResult AnalyzeAudio(
  const std::vector<float>& samples,
  double sample_rate,
  double supplied_duration
);

TempoResult AnalyzeTempo(
  const std::vector<float>& samples,
  double sample_rate,
  double duration,
  double audible_start
);

}
