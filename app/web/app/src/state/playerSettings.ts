import { AUTOMIX_BARS } from '@/playback/automix/choices';
import { trackLog } from '@/playback/trackLog';

export type RepeatMode = 'off' | 'all' | 'one';

export const CROSSFADE_CHOICES = [0, 3, 6, 9, 12] as const;
export const LOUDNESS_TARGETS = [-20, -18, -16, -14, -12, -10] as const;

// YouTube's loudnessDb is relative to its own -14 LUFS reference; gain is capped at +3 dB like Android.
export function loudnessGain(loudnessDb: number | null, targetLufs: number | null): number {
  if (loudnessDb === null || targetLufs === null) return 1;
  const db = Math.min(3, Math.max(-15, targetLufs + 14 - loudnessDb));
  return 10 ** (db / 20);
}

export interface PlayerSettings {
  shuffle: boolean;
  repeat: RepeatMode;
  autoplay: boolean;
  volume: number;
  muted: boolean;
  crossfadeSec: number;
  automix: boolean;
  automixBars: number;
  showNerdStats: boolean;
  loudnessTarget: number | null;
}

const SETTINGS_KEY = 'nuvx:player';
const DEFAULT_SETTINGS: PlayerSettings = {
  shuffle: false,
  repeat: 'off',
  autoplay: true,
  volume: 1,
  muted: false,
  crossfadeSec: 0,
  automix: false,
  automixBars: 16,
  showNerdStats: false,
  loudnessTarget: null,
};

export function loadPlayerSettings(): PlayerSettings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as Partial<Record<keyof PlayerSettings, unknown>>;
    return {
      shuffle: typeof saved.shuffle === 'boolean' ? saved.shuffle : DEFAULT_SETTINGS.shuffle,
      repeat: saved.repeat === 'all' || saved.repeat === 'one' || saved.repeat === 'off' ? saved.repeat : DEFAULT_SETTINGS.repeat,
      autoplay: typeof saved.autoplay === 'boolean' ? saved.autoplay : DEFAULT_SETTINGS.autoplay,
      volume: typeof saved.volume === 'number' && Number.isFinite(saved.volume) && saved.volume >= 0 && saved.volume <= 1
        ? saved.volume : DEFAULT_SETTINGS.volume,
      muted: typeof saved.muted === 'boolean' ? saved.muted : DEFAULT_SETTINGS.muted,
      crossfadeSec: CROSSFADE_CHOICES.includes(saved.crossfadeSec as (typeof CROSSFADE_CHOICES)[number])
        ? saved.crossfadeSec as number : DEFAULT_SETTINGS.crossfadeSec,
      automix: typeof saved.automix === 'boolean' ? saved.automix : DEFAULT_SETTINGS.automix,
      automixBars: AUTOMIX_BARS.includes(saved.automixBars as (typeof AUTOMIX_BARS)[number])
        ? saved.automixBars as number : DEFAULT_SETTINGS.automixBars,
      showNerdStats: typeof saved.showNerdStats === 'boolean' ? saved.showNerdStats : DEFAULT_SETTINGS.showNerdStats,
      loudnessTarget: LOUDNESS_TARGETS.includes(saved.loudnessTarget as (typeof LOUDNESS_TARGETS)[number])
        ? saved.loudnessTarget as number : DEFAULT_SETTINGS.loudnessTarget,
    };
  } catch {
    return DEFAULT_SETTINGS;
  }
}

export function savePlayerSettings(state: PlayerSettings, onSaveError: (error: string | null) => void) {
  const { shuffle, repeat, autoplay, volume, muted, crossfadeSec, automix, automixBars, showNerdStats, loudnessTarget } = state;
  try {
    localStorage.setItem(
      SETTINGS_KEY,
      JSON.stringify({ shuffle, repeat, autoplay, volume, muted, crossfadeSec, automix, automixBars, showNerdStats, loudnessTarget }),
    );
    onSaveError(null);
  } catch (error) {
    onSaveError('Player settings could not be saved. They are still active until you close this tab.');
    trackLog.w(`player settings could not be saved: ${String(error)}`);
  }
}
