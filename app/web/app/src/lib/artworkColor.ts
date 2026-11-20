import { useEffect, useState } from 'react';
import { artworkAtPixels } from './artwork';

export interface ArtworkColor {
  background: string;
  dark: boolean;
}

const cache = new Map<string, ArtworkColor>();

async function measure(url: string, signal: AbortSignal): Promise<ArtworkColor> {
  const image = new Image();
  image.crossOrigin = 'anonymous';
  image.referrerPolicy = 'no-referrer';
  image.decoding = 'async';
  const abort = () => { image.src = ''; };
  signal.addEventListener('abort', abort, { once: true });
  try {
    if (signal.aborted) throw new DOMException('Artwork request cancelled', 'AbortError');
    image.src = artworkAtPixels(url, 60);
    await image.decode();
    if (signal.aborted) throw new DOMException('Artwork request cancelled', 'AbortError');
  } finally {
    signal.removeEventListener('abort', abort);
  }
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 8;
  const context = canvas.getContext('2d', { willReadFrequently: true })!;
  context.drawImage(image, 0, 0, 8, 8);
  const { data } = context.getImageData(0, 0, 8, 8);
  let r = 0;
  let g = 0;
  let b = 0;
  for (let i = 0; i < data.length; i += 4) {
    r += data[i]!;
    g += data[i + 1]!;
    b += data[i + 2]!;
  }
  const n = data.length / 4;
  [r, g, b] = [r / n, g / n, b / n];
  const luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  const dark = luminance < 0.6;
  const k = dark ? Math.min(1, 0.55 / Math.max(luminance, 0.05)) * 0.6 : 1;
  const scale = (v: number) => Math.round(dark ? Math.min(v * k, 90) : v);
  return { background: `rgb(${scale(r)}, ${scale(g)}, ${scale(b)})`, dark };
}

export function useArtworkColor(url: string | null | undefined): ArtworkColor | null {
  const [current, setCurrent] = useState<{ url: string; color: ArtworkColor } | null>(() => {
    const known = url ? cache.get(url) : null;
    return url && known ? { url, color: known } : null;
  });
  useEffect(() => {
    if (!url) {
      setCurrent(null);
      return;
    }
    const known = cache.get(url);
    if (known) {
      setCurrent({ url, color: known });
      return;
    }
    const controller = new AbortController();
    void measure(url, controller.signal)
      .then((measured) => {
        if (controller.signal.aborted) return;
        cache.set(url, measured);
        setCurrent({ url, color: measured });
      })
      .catch(() => {
        // An obsolete or failed thumbnail must not overwrite the current page.
      });
    return () => controller.abort();
  }, [url]);
  // Reject the previous page's tint during render, even before effect cleanup runs.
  return current && current.url === url ? current.color : (url ? cache.get(url) ?? null : null);
}
