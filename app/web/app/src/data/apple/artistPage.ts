export interface AppleArtistArt {
  heroUrl: string;
  heroAspect: number;
  logoUrl: string | null;
  logoAspect: number;
  background: string;
  accent: string;
  keyColor: string | null;
}

const HERO_WIDTH = 1200;
const LOGO_WIDTH = 1000;

export type Json = Record<string, unknown>;
export const obj = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null);
export const str = (v: unknown): string => (typeof v === 'string' ? v : '');
export const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
export function parse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function normalized(name: string): string {
  return name.toLowerCase().normalize('NFD').replace(/[^\p{L}\p{N}]/gu, '');
}

function hex(value: unknown): string | null {
  const v = str(value);
  return /^[0-9a-f]{6}$/i.test(v) ? `#${v.toLowerCase()}` : null;
}

function fill(template: unknown, w: number, h: number, format: string): string | null {
  const url = str(template);
  if (!url.includes('{w}')) return null;
  return url.replace('{w}', String(w)).replace('{h}', String(h)).replace('{c}', 'bb').replace('{f}', format);
}

function findHeader(node: unknown): Json | null {
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = findHeader(child);
      if (found) return found;
    }
    return null;
  }
  const o = obj(node);
  if (!o) return null;
  if ('artistLogo' in o) return o;
  for (const child of Object.values(o)) {
    const found = findHeader(child);
    if (found) return found;
  }
  return null;
}

export function parseArtistPage(html: string): AppleArtistArt | null {
  const marker = html.indexOf('id="serialized-server-data"');
  if (marker < 0) return null;
  const start = html.indexOf('>', marker) + 1;
  const end = html.indexOf('</script>', start);
  if (start <= 0 || end < 0) return null;
  const header = findHeader(parse(html.slice(start, end)));
  if (!header) return null;

  const logo = obj(obj(header.artistLogo)?.dictionary);
  const hero = obj(obj(header.artwork)?.dictionary) ?? obj(obj(header.colorBackdropArtwork)?.dictionary);
  if (!hero) return null;
  const heroW = num(hero.width);
  const heroH = num(hero.height);
  if (!heroW || !heroH) return null;
  const heroUrl = fill(hero.url, HERO_WIDTH, Math.round((HERO_WIDTH * heroH) / heroW), 'jpg');
  if (!heroUrl) return null;

  const logoW = num(logo?.width);
  const logoH = num(logo?.height);
  const logoUrl = logo && logoW && logoH ? fill(logo.url, LOGO_WIDTH, Math.round((LOGO_WIDTH * logoH) / logoW), 'png') : null;

  const background = hex(hero.bgColor);
  if (!background) return null;
  return {
    heroUrl,
    heroAspect: heroW / heroH,
    logoUrl,
    logoAspect: logoUrl ? logoW / logoH : 1,
    background,
    accent: hex(hero.textColor1) ?? background,
    keyColor: hex(header.keyColor),
  };
}
