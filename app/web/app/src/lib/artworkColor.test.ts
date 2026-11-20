// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./artwork', () => ({ artworkAtPixels: (url: string) => url }));

import { useArtworkColor } from './artworkColor';

const pending = new Map<string, () => void>();
const images: FakeImage[] = [];

class FakeImage {
  private value = '';
  crossOrigin = '';
  referrerPolicy = '';
  decoding = '';
  get src() { return this.value; }
  set src(next: string) { this.value = next; }
  decode(): Promise<void> {
    return new Promise((resolve) => { pending.set(this.src, resolve); });
  }
}

function Harness({ url }: { url: string | null }) {
  const color = useArtworkColor(url);
  return createElement('span', null, color?.background ?? 'none');
}

describe('artwork tint request cancellation', () => {
  beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    vi.stubGlobal('Image', class extends FakeImage {
      constructor() {
        super();
        images.push(this);
      }
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({
      drawImage: () => {},
      getImageData: () => ({ data: new Uint8ClampedArray(8 * 8 * 4).fill(100) }),
    }) as unknown as CanvasRenderingContext2D);
  });

  afterEach(() => {
    pending.clear();
    images.length = 0;
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.body.replaceChildren();
  });

  it('aborts an obsolete image and ignores out-of-order completion', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    act(() => root.render(createElement(Harness, { url: 'old-artwork' })));
    expect(images[0]?.src).toBe('old-artwork');

    act(() => root.render(createElement(Harness, { url: 'new-artwork' })));
    expect(images[0]?.src).toBe('');
    expect(images[1]?.src).toBe('new-artwork');
    expect(host.textContent).toBe('none');

    await act(async () => { pending.get('new-artwork')!(); });
    expect(host.textContent).toMatch(/^rgb\(/);
    const latest = host.textContent;

    await act(async () => { pending.get('old-artwork')!(); });
    expect(host.textContent).toBe(latest);

    act(() => root.unmount());
  });
});
