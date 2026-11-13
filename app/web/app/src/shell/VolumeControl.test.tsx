// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const store = vi.hoisted(() => ({
  volume: 0.5,
  muted: false,
  setVolume: vi.fn(),
  toggleMute: vi.fn(),
}));

vi.mock('@/state/player', () => ({
  usePlayer: Object.assign((selector: (state: typeof store) => unknown) => selector(store), { getState: () => store }),
}));
vi.mock('@/components/icons/Transport', () => ({ VolumeIcon: () => null }));

import { VolumeControl } from './VolumeControl';

describe('volume wheel control', () => {
  beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    store.volume = 0.5;
    store.muted = false;
    store.setVolume.mockClear();
  });
  afterEach(() => { vi.unstubAllGlobals(); document.body.replaceChildren(); });

  it('changes volume on the control but leaves wheel input on the slider to the browser', () => {
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    act(() => root.render(createElement(VolumeControl, { open: true, onOpen: vi.fn(), className: 'volume' })));
    const button = host.querySelector('button')!;
    const slider = host.querySelector('input[type="range"]')!;

    const controlWheel = new WheelEvent('wheel', { deltaY: -100, bubbles: true, cancelable: true });
    button.dispatchEvent(controlWheel);
    expect(controlWheel.defaultPrevented).toBe(true);
    expect(store.setVolume).toHaveBeenCalledWith(0.55);

    const sliderWheel = new WheelEvent('wheel', { deltaY: -100, bubbles: true, cancelable: true });
    slider.dispatchEvent(sliderWheel);
    expect(sliderWheel.defaultPrevented).toBe(false);
    expect(store.setVolume).toHaveBeenCalledTimes(1);

    act(() => root.unmount());
  });
});
