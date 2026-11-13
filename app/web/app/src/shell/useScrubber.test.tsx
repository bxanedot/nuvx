// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  audio: { currentTime: 25, duration: 100, paused: true, addEventListener: vi.fn(), removeEventListener: vi.fn() },
  state: { status: 'paused', deck: 0, mixing: false, queue: [{ track: { streamUrl: null } }], index: 0, seek: vi.fn() },
}));

vi.mock('@/state/player', () => ({
  getAudio: () => mocks.audio,
  getMixBeat: () => null,
  getPlayhead: () => ({ sec: mocks.audio.currentTime, paused: mocks.audio.paused }),
  usePlayer: (selector: (state: typeof mocks.state) => unknown) => selector(mocks.state),
}));

import { useScrubber } from './useScrubber';

function Harness() {
  const scrubber = useScrubber();
  return createElement('div', {
    ref: scrubber.bar,
    role: 'slider',
    tabIndex: 0,
    ...scrubber.handlers,
    'data-dragging': scrubber.dragging,
  });
}

describe('seek scrubber boundaries', () => {
  let host: HTMLDivElement;
  let root: ReturnType<typeof createRoot>;

  beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    mocks.audio.currentTime = 25;
    mocks.audio.duration = 100;
    mocks.audio.paused = true;
    mocks.state.status = 'paused';
    mocks.state.queue = [{ track: { streamUrl: null } }];
    mocks.state.index = 0;
    mocks.state.seek.mockClear();
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    act(() => root.render(createElement(Harness)));
    const slider = host.querySelector('[role="slider"]') as HTMLDivElement;
    slider.getBoundingClientRect = () => ({ left: 0, right: 100, top: 0, bottom: 10, width: 100, height: 10, x: 0, y: 0, toJSON: () => ({}) });
    slider.setPointerCapture = vi.fn();
  });

  afterEach(() => {
    act(() => root.unmount());
    vi.unstubAllGlobals();
    document.body.replaceChildren();
  });

  const slider = () => host.querySelector('[role="slider"]') as HTMLDivElement;
  const pointer = (type: string, clientX = 50) => new PointerEvent(type, { pointerId: 1, clientX, bubbles: true });

  it('clears a drag on Escape, pointer cancellation, and lost pointer capture', () => {
    for (const cancel of [
      () => slider().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })),
      () => slider().dispatchEvent(pointer('pointercancel')),
      () => slider().dispatchEvent(pointer('lostpointercapture')),
    ]) {
      act(() => slider().dispatchEvent(pointer('pointerdown')));
      expect(slider().dataset.dragging).toBe('true');
      act(cancel);
      expect(slider().dataset.dragging).toBe('false');
    }
    expect(mocks.state.seek).not.toHaveBeenCalled();
  });

  it('does not issue keyboard or pointer seeks for an unknown duration', () => {
    mocks.audio.duration = Number.POSITIVE_INFINITY;
    const key = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
    slider().dispatchEvent(key);
    expect(key.defaultPrevented).toBe(false);

    act(() => slider().dispatchEvent(pointer('pointerdown')));
    act(() => slider().dispatchEvent(pointer('pointerup', 90)));
    expect(mocks.state.seek).not.toHaveBeenCalled();
    expect(slider().dataset.dragging).toBe('false');
  });
});
