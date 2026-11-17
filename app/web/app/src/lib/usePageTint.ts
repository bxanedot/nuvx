import { useEffect } from 'react';
import type { ArtworkColor } from './artworkColor';

export function usePageTint(color: ArtworkColor | null) {
  useEffect(() => {
    const page = document.getElementById('scrollable-page');
    if (!page || !color) return;
    const channels = color.background.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/i);
    const tint = channels
      ? Math.round(Number(channels[1]) * 0.2126 + Number(channels[2]) * 0.7152 + Number(channels[3]) * 0.0722)
      : color.dark ? 24 : 240;
    page.style.setProperty('--page-tint', `rgb(${tint}, ${tint}, ${tint})`);
    page.dataset.tint = color.dark ? 'dark' : 'light';
    return () => {
      page.style.removeProperty('--page-tint');
      delete page.dataset.tint;
    };
  }, [color]);
}
