// Port of app/desktop/src/hires/matching.rs `metadata_matches`: same title/version/artist/duration rules.
import { isSevereMismatch } from '@/data/match/trackMatcher';
const VERSION_WORDS = new Set(
  ('remix remixes rmx refix flip bootleg mashup medley live concert unplugged acoustic instrumental karaoke vocals vocal acapella acappella backing stems stem cover demo reprise remake rework extended edit version mix dub vip session sessions sped slowed reverb nightcore lofi orchestral symphonic part pt chapter').split(' '),
);
const PLAIN_VERSIONS = new Set(
  'albumversion originalversion originalmix singleversion radioversion radioedit stereoversion monoversion studioversion fullversion standardversion explicitversion deluxeversion originaltrack'.split(' '),
);
const TRAILING_NOISE = new Set('song songs video audio lyrics official full hd hq 4k mp3 ost soundtrack'.split(' '));
const FEAT = [' feat.', ' feat ', ' ft.', ' ft ', ' featuring ', ' with '];

const lower = (s: string) => s.replace(/[A-Z]/g, (c) => c.toLowerCase());
const clean = (w: string) => w.replace(/[^A-Za-z0-9]/g, '');
const split = (s: string, pattern: RegExp) => s.split(pattern).map(clean).filter(Boolean);
const wordsOf = (s: string) => split(s, /[ .·]/);

function classify(segment: string, versions: Set<string>) {
  const words = wordsOf(segment);
  if (!words.length || PLAIN_VERSIONS.has(words.join(''))) return;
  for (const word of words) if (VERSION_WORDS.has(word)) versions.add(word);
}

function isArtistName(text: string, artist: string): boolean {
  if (!artist) return false;
  const words = wordsOf(text);
  const credited = wordsOf(lower(artist));
  return words.length > 0 && words.every((w) => credited.includes(w));
}

function titleParts(raw: string, artist: string): [string, string] {
  const versions = new Set<string>();
  let text = lower(raw).replaceAll('&', ' and ');
  for (let i = 0; i < 3; i++) {
    const open = text.search(/[([]/);
    const close = open < 0 ? -1 : text.slice(open).search(/[)\]]/);
    if (open < 0 || close < 0) break;
    classify(text.slice(open + 1, open + close), versions);
    text = `${text.slice(0, open)} ${text.slice(open + close + 1)}`;
  }
  const at = text.search(/[([]/);
  if (at >= 0) {
    classify(text.slice(at), versions);
    text = text.slice(0, at);
  }
  for (let i = 0; i < 3; i++) {
    const dash = text.indexOf(' - ');
    if (dash < 0) break;
    const head = text.slice(0, dash);
    const tail = text.slice(dash + 3);
    if (isArtistName(head, artist)) {
      classify(head, versions);
      text = tail;
    } else {
      classify(tail, versions);
      text = head;
    }
  }
  for (const marker of FEAT) {
    const cut = text.indexOf(marker);
    if (cut >= 0) text = text.slice(0, cut);
  }
  const words = wordsOf(text).filter((w) => w !== 'and');
  while (words.length > 1 && TRAILING_NOISE.has(words[words.length - 1]!)) words.pop();
  return [words.join(' '), [...versions].sort().join(',')];
}

function artistNames(value: string): string[][] {
  const normalized = lower(value).trim();
  if (!normalized || ['unknown', 'unknown artist', 'unknown artist(s)'].includes(normalized)) return [];
  return normalized
    .split(/[,&/;·|]/)
    .map((name) => {
      const parts = split(name, /[ .]/).filter((w) => w.length > 1);
      return parts.length ? parts : [name.trim()];
    })
    .filter((parts) => parts.some(Boolean));
}

const contains = (outer: string[], inner: string[]) =>
  inner.length > 0 && inner.length <= outer.length && outer.some((_, i) => inner.every((w, j) => outer[i + j] === w));

function orderedSubset(shorter: string[], longer: string[]): boolean {
  if (!shorter.length || shorter.length >= longer.length) return false;
  let at = 0;
  for (const word of shorter) {
    const pos = longer.indexOf(word, at);
    if (pos < 0) return false;
    at = pos + 1;
  }
  return true;
}

const sameArtist = (a: string[], b: string[]) => contains(a, b) || contains(b, a) || orderedSubset(a, b) || orderedSubset(b, a);

export interface Described {
  title: string;
  artist: string;
  durationSec: number | null;
}

export function metadataMatches(want: Described, got: Described): boolean {
  const [title, versions] = titleParts(want.title, want.artist);
  const [gotTitle, gotVersions] = titleParts(got.title, got.artist);
  if (!title || title !== gotTitle || versions !== gotVersions) return false;
  const wanted = artistNames(want.artist);
  const found = artistNames(got.artist);
  if (wanted.length && (!found.length || !wanted.some((w) => found.some((g) => sameArtist(w, g))))) return false;
  if (want.durationSec && got.durationSec && isSevereMismatch(want.durationSec, got.durationSec)) return false;
  return true;
}
