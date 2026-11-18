import { beforeEach, expect, test, vi } from 'vitest';

let useUi: typeof import('./ui').useUi;
function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
  };
}

beforeEach(async () => {
  vi.resetModules();
  vi.stubGlobal('window', { localStorage: storage(), sessionStorage: storage() });
  ({ useUi } = await import('./ui'));
});

test('monochrome background setting persists', () => {
  useUi.getState().setBackgroundMonochrome(true);
  expect(useUi.getState().backgroundMonochrome).toBe(true);
  expect(window.localStorage.getItem('nuvx:background-monochrome')).toBe('on');
});

test('monochrome background setting restores from localStorage', async () => {
  window.localStorage.setItem('nuvx:background-monochrome', 'on');
  vi.resetModules();
  const { useUi: restoredUi } = await import('./ui');
  expect(restoredUi.getState().backgroundMonochrome).toBe(true);
});
