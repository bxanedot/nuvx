import { describe, expect, it } from 'vitest';
import { youTubeThumbnailFallback } from './artwork';

describe('youTubeThumbnailFallback', () => {
  const hq = 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg';
  it('maps large ytimg thumbnails to hqdefault', () => {
    expect(youTubeThumbnailFallback('https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg')).toBe(hq);
    expect(youTubeThumbnailFallback('https://i9.ytimg.com/vi_webp/dQw4w9WgXcQ/sddefault.webp?sqp=x')).toBe(hq);
  });
  it('leaves other images alone', () => {
    expect(youTubeThumbnailFallback(hq)).toBeNull();
    expect(youTubeThumbnailFallback('https://lh3.googleusercontent.com/abc=w544-h544')).toBeNull();
  });
});
