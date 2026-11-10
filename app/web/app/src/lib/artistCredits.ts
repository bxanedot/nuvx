import type { ArtistRef } from '@/data/music/types';

export interface CreditSegment {
  text: string;
  credit: ArtistRef | null;
}

const CREDIT_JOINER = /\s*,\s*|\s*，\s*|\s+&\s+|\s+＆\s+|\s*•\s*|\s*·\s*|\s*・\s*|\s*、\s*/g;

export function normalizedArtistName(raw: string): string {
  return raw.normalize('NFD').replace(/[̀-ͯ]+/g, '').toLowerCase().replace(/\s+/g, ' ').trim();
}

export function isExactArtistMatch(answer: string, wanted: string): boolean {
  const name = normalizedArtistName(wanted);
  return name !== '' && normalizedArtistName(answer) === name;
}

function splitCreditLine(artist: string): ArtistRef[] {
  return artist
    .trim()
    .split(CREDIT_JOINER)
    .map((name) => name.trim())
    .filter(Boolean)
    .map((name) => ({ name, id: null }));
}

export function artistCredits(artist: string, artists: readonly ArtistRef[]): ArtistRef[] {
  const stated = artists.filter((a) => a.name.trim());
  const wholeLine = stated.length === 1 && !stated[0]!.id && new RegExp(CREDIT_JOINER.source).test(stated[0]!.name);
  return stated.length && !wholeLine ? stated : splitCreditLine(stated[0]?.name ?? artist);
}

export function creditSegments(text: string, credits: readonly ArtistRef[]): CreditSegment[] {
  if (!text) return [];
  const seen = new Set<string>();
  const names = credits
    .filter((c) => c.name.trim())
    .filter((c) => {
      const key = normalizedArtistName(c.name);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((a, b) => b.name.length - a.name.length);
  if (!names.length) return [{ text, credit: null }];

  const out: CreditSegment[] = [];
  let plain = '';
  let index = 0;
  while (index < text.length) {
    const hit = names.find((c) => text.slice(index, index + c.name.length).toLowerCase() === c.name.toLowerCase());
    if (!hit) {
      plain += text[index];
      index++;
      continue;
    }
    if (plain) {
      out.push({ text: plain, credit: null });
      plain = '';
    }
    out.push({ text: text.slice(index, index + hit.name.length), credit: hit });
    index += hit.name.length;
  }
  if (plain) out.push({ text: plain, credit: null });
  return out;
}
