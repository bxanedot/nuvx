// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace nuvx::loopmix {

struct Envelope {
  std::vector<double> times;
  std::vector<float> values;
  bool exponential = false;

  bool empty() const { return times.empty(); }
  float at(double time) const;
};

struct Biquad {
  float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
  float z1[2] = {0, 0};
  float z2[2] = {0, 0};

  void lowShelf(double sampleRate, double frequency, double gainDb);
  void highPass(double sampleRate, double frequency, double q);
  void bypass() { b0 = 1; b1 = b2 = a1 = a2 = 0; }
  inline float tick(int channel, float x) {
    const float y = b0 * x + z1[channel];
    z1[channel] = b1 * x - a1 * y + z2[channel];
    z2[channel] = b2 * x - a2 * y;
    return y;
  }
};

struct Deck {
  std::vector<float> left;
  std::vector<float> right;
  double regionStart = 0;

  int64_t loopStart = 0;
  int64_t loopEnd = 0;
  double releaseAt = 1e18;

  std::vector<double> knotTimes;
  std::vector<double> knotPositions;
  bool runOn = false;
  double stopAt = 1e18;
  double preroll = 0.4;
  float level = 1;

  Envelope gain;
  Envelope lowDb;
  Envelope highPassHz;

  std::atomic<double> fadeOutAt{-1.0};
  std::atomic<double> heard{0.0};

  signalsmith::stretch::SignalsmithStretch<float> stretch;
  bool started = false;
  bool finished = false;
  double readPos = 0;
  double heardPos = 0;
  double lastKnotHeard = 0;
  Biquad shelf;
  Biquad highPass;
  float shelfDb = 0;
  float highPassFreq = 0;

  double trackSecondsAt(double unwrapped, double sampleRate) const;
  double releasedPositionOf(double seconds, double sampleRate) const;
  int64_t sampleAt(int64_t unwrapped) const;
  double knotHeard(double t) const;
  double knotRate(double t) const;
};

class Renderer {
 public:
  Renderer(int sampleRate);

  Deck& deck(int index) { return decks_[index]; }
  int sampleRate() const { return sampleRate_; }

  void prepare();

  int64_t firstFrame() const;

  void render(int64_t frame, float* interleaved, int frames);

 private:
  void renderDeck(Deck& deck, int64_t frame, int frames);

  int sampleRate_;
  Deck decks_[2];
  std::vector<float> inLeft_, inRight_, outLeft_, outRight_;
  std::vector<float> mixLeft_, mixRight_;
};

}
