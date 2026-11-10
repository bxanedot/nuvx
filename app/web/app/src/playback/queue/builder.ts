import type { Track } from '@/data/music/types';

export const PER_ARTIST_LIMIT = 2;
export const SEED_ARTIST_LIMIT = 4;

export interface Candidate {
  track: Track;
  isVideo: boolean;
}

const NOISE = new RegExp(
  String.raw`\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)` +
    String.raw`|\(from[^)]*\)` +
    String.raw`|\[[^\]]*\]` +
    String.raw`|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b`,
  'giu',
);
const TOPIC = /\s*-\s*topic\b/giu;
const PUNCTUATION = /[^\p{L}\p{N}]+/gu;
const SPACES = /\s+/g;
const CREDIT_SEPARATORS = /,|&|·|•|;| feat| ft\.| ft | x | with /;

export const artistLine = (track: Track) => track.artists.map((a) => a.name).join(', ');

export function normalisedTitle(raw: string): string {
  return raw.toLowerCase().split(' | ')[0]!.replace(NOISE, ' ').replace(PUNCTUATION, ' ').replace(SPACES, ' ').trim();
}

export function artistSet(raw: string): Set<string> {
  return new Set(
    raw
      .toLowerCase()
      .replace(TOPIC, ' ')
      .split(CREDIT_SEPARATORS)
      .map((part) => part.replace(PUNCTUATION, ' ').replace(SPACES, ' ').trim())
      .filter(Boolean),
  );
}

export function isSameRecording(a: Track, b: Track): boolean {
  if (a.videoId === b.videoId) return true;
  const title = normalisedTitle(a.title);
  if (!title || title !== normalisedTitle(b.title)) return false;
  const left = artistSet(artistLine(a));
  const right = artistSet(artistLine(b));
  return !left.size || !right.size || [...left].some((name) => right.has(name));
}

export function extendStation(
  existing: readonly Track[],
  candidates: readonly Candidate[],
  limit: number,
  preferVideo = false,
): Track[] {
  const taken = [...existing];
  const perArtist = new Map<string, number>();
  const last = existing.at(-1);
  const seedArtists = last ? artistSet(artistLine(last)) : new Set<string>();
  const out: Track[] = [];

  const wanted = candidates.filter((c) => c.isVideo === preferVideo);
  const ordered = (wanted.length ? wanted : candidates).map((c) => c.track);

  for (const candidate of ordered) {
    if (out.length >= limit) break;
    if (taken.some((t) => isSameRecording(t, candidate))) continue;
    const artists = [...artistSet(artistLine(candidate))];
    const key = artists.sort()[0];
    if (key !== undefined) {
      const cap = artists.some((a) => seedArtists.has(a)) ? SEED_ARTIST_LIMIT : PER_ARTIST_LIMIT;
      const played = perArtist.get(key) ?? 0;
      if (played >= cap) continue;
      perArtist.set(key, played + 1);
    }
    taken.push(candidate);
    out.push(candidate);
  }
  return out;
}
