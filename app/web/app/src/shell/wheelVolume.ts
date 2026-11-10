export const WHEEL_VOLUME_STEP = 0.05;

export function nextVolumeFromWheel(volume: number, deltaY: number): number {
  if (deltaY === 0) return volume;
  const next = volume + (deltaY < 0 ? WHEEL_VOLUME_STEP : -WHEEL_VOLUME_STEP);
  return Math.round(Math.min(1, Math.max(0, next)) * 100) / 100;
}
