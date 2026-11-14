const HOP_MS = 10;
const MAX_LAG_FRAMES = 6_000;
const MIN_OVERLAP_FRAMES = 300;
const MIN_CONFIDENCE = 0.4;
const MIN_PEAK_MARGIN = 0.03;
const PEAK_SEPARATION_FRAMES = 30;
const WINDOW_MS = 40;
const ANALYSIS_SECONDS = 90;

export function energyEnvelope(samples: Float32Array, channels: number, rate: number): Float32Array {
  if (channels <= 0 || rate <= 0) return new Float32Array();
  const frameCount = Math.floor(samples.length / channels);
  const hopFrames = Math.max(1, Math.floor((rate * HOP_MS) / 1000));
  const windowFrames = Math.max(hopFrames, Math.floor((rate * WINDOW_MS) / 1000));
  if (frameCount < windowFrames) return new Float32Array();
  const count = Math.floor((frameCount - windowFrames) / hopFrames) + 1;
  const envelope = new Float32Array(count);
  for (let frame = 0; frame < count; frame++) {
    const start = frame * hopFrames * channels;
    const end = start + windowFrames * channels;
    let sum = 0;
    for (let index = start; index < end; index++) sum += samples[index]! * samples[index]!;
    envelope[frame] = Math.sqrt(sum / (windowFrames * channels));
  }
  return envelope;
}

export function alignAudio(source: Float32Array, target: Float32Array): number | null {
  if (source.length < MIN_OVERLAP_FRAMES || target.length < MIN_OVERLAP_FRAMES) return null;
  const minLag = -Math.min(MAX_LAG_FRAMES, source.length - MIN_OVERLAP_FRAMES);
  const maxLag = Math.min(MAX_LAG_FRAMES, target.length - MIN_OVERLAP_FRAMES);
  let bestLag = 0;
  let bestCorrelation = -1;
  const correlations: Array<[number, number]> = [];
  for (let lag = minLag; lag <= maxLag; lag++) {
    const sourceStart = Math.max(0, -lag);
    const targetStart = Math.max(0, lag);
    const overlap = Math.min(source.length - sourceStart, target.length - targetStart);
    if (overlap < MIN_OVERLAP_FRAMES) continue;
    let sourceSum = 0;
    let targetSum = 0;
    let sourceSq = 0;
    let targetSq = 0;
    let cross = 0;
    for (let index = 0; index < overlap; index++) {
      const x = source[sourceStart + index]!;
      const y = target[targetStart + index]!;
      sourceSum += x;
      targetSum += y;
      sourceSq += x * x;
      targetSq += y * y;
      cross += x * y;
    }
    const n = overlap;
    const covariance = cross - (sourceSum * targetSum) / n;
    const sourceVariance = sourceSq - (sourceSum * sourceSum) / n;
    const targetVariance = targetSq - (targetSum * targetSum) / n;
    const denominator = Math.sqrt(sourceVariance * targetVariance);
    if (denominator > 1e-6) {
      const correlation = covariance / denominator;
      correlations.push([lag, correlation]);
      if (correlation > bestCorrelation) {
        bestCorrelation = correlation;
        bestLag = lag;
      }
    }
  }
  if (bestCorrelation < MIN_CONFIDENCE || bestLag === minLag || bestLag === maxLag) return null;
  if (
    correlations.some(
      ([lag, correlation]) =>
        Math.abs(lag - bestLag) >= PEAK_SEPARATION_FRAMES && bestCorrelation - correlation < MIN_PEAK_MARGIN,
    )
  ) {
    return null;
  }
  return bestLag * HOP_MS;
}

export function analyseAudio(samples: Float32Array, channels: number, rate: number, seconds = ANALYSIS_SECONDS): Float32Array {
  const limit = Math.min(samples.length, Math.floor(rate * seconds) * channels);
  return energyEnvelope(samples.subarray(0, limit), channels, rate);
}
