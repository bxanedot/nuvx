const SQUARE_SIZES = [60, 120, 226, 544, 1200];

const RESIZABLE = /googleusercontent\.com|ggpht\.com/;

export function sizedArtwork(url: string, cssPx: number, aspect = 1): string {
  if (!RESIZABLE.test(url) || Math.abs(aspect - 1) > 0.01) return url;
  const dpr = Math.min(typeof window === 'undefined' ? 2 : window.devicePixelRatio || 1, 3);
  const needed = cssPx * dpr;
  const size = SQUARE_SIZES.find((s) => s >= needed) ?? SQUARE_SIZES[SQUARE_SIZES.length - 1]!;
  return artworkAtPixels(url, size);
}

export function artworkAtPixels(url: string, px: number): string {
  if (!RESIZABLE.test(url)) return url;
  return `${url.replace(/=[^/=]*$/, '')}=w${px}-h${px}-l90-rj`;
}
