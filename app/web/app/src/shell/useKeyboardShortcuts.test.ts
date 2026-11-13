// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  player: { togglePlay: vi.fn(), next: vi.fn(), previous: vi.fn(), setVolume: vi.fn(), volume: 0.5 },
  ui: { fullScreen: false, sidePanel: false, setFullScreen: vi.fn(), closeSidePanel: vi.fn() },
}));

vi.mock('react-router', () => ({ useNavigate: () => mocks.navigate }));
vi.mock('@/state/player', () => ({ usePlayer: { getState: () => mocks.player } }));
vi.mock('@/state/ui', () => ({ useUi: { getState: () => mocks.ui } }));

import { useKeyboardShortcuts } from './useKeyboardShortcuts';

function Harness() {
  useKeyboardShortcuts();
  return null;
}

describe('player keyboard shortcuts', () => {
  beforeEach(() => vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true));
  afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    document.body.replaceChildren();
  });

  it('does not trigger playback shortcuts while typing in editable controls', () => {
    const root = createRoot(document.createElement('div'));
    act(() => root.render(createElement(Harness)));
    const input = document.createElement('input');
    const area = document.createElement('textarea');
    const editable = document.createElement('div');
    editable.contentEditable = 'true';
    document.body.append(input, area, editable);

    for (const [target, key] of [[input, ' '], [area, 'ArrowRight'], [editable, 'ArrowLeft']] as const) {
      const event = new KeyboardEvent('keydown', { key, ctrlKey: key !== ' ', bubbles: true, cancelable: true });
      target.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    }
    expect(mocks.player.togglePlay).not.toHaveBeenCalled();
    expect(mocks.player.next).not.toHaveBeenCalled();
    expect(mocks.player.previous).not.toHaveBeenCalled();

    act(() => root.unmount());
  });

  it('removes its key listener on unmount and never duplicates actions after remounts', () => {
    const host = document.createElement('div');
    document.body.append(host);
    for (let mount = 1; mount <= 3; mount++) {
      const root = createRoot(host);
      act(() => root.render(createElement(Harness)));
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
      expect(mocks.player.togglePlay).toHaveBeenCalledTimes(mount);
      act(() => root.unmount());
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true }));
      expect(mocks.player.togglePlay).toHaveBeenCalledTimes(mount);
    }
  });
});
