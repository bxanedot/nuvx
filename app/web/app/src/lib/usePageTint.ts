import { useEffect } from 'react';
import type { ArtworkColor } from './artworkColor';

export function usePageTint(color: ArtworkColor | null) {
  useEffect(() => {
    const page = document.getElementById('scrollable-page');
    if (!page || !color) return;
    page.style.setProperty('--page-tint', color.background);
    page.dataset.tint = color.dark ? 'dark' : 'light';
    return () => {
      page.style.removeProperty('--page-tint');
      delete page.dataset.tint;
    };
  }, [color]);
}
