import { afterEach, describe, expect, it, vi } from 'vitest';

const { resolvePlayer, pending } = vi.hoisted(() => ({
  resolvePlayer: vi.fn(),
  pending: new Map<string, { resolve: (value: unknown) => void; reject: (reason: unknown) => void }>(),
}));

vi.mock('googlevideo/sabr-streaming-adapter', () => ({ SabrStreamingAdapter: class {} }));
vi.mock('googlevideo/utils', () => ({ buildSabrFormat: vi.fn() }));
vi.mock('shaka-player/dist/shaka-player.compiled', () => ({
  default: {
    polyfill: { installAll: vi.fn() },
    util: { Error: Object.assign(class extends Error {}, { Severity: { CRITICAL: 2 }, Code: { BAD_HTTP_STATUS: 1, HTTP_ERROR: 2 } }) },
    Player: Object.assign(class {
      configure() {}
      async attach() {}
      addEventListener() {}
    }, { isBrowserSupported: () => true }),
    net: { NetworkingEngine: { registerScheme: vi.fn(), PluginPriority: { FALLBACK: 0 } }, HttpFetchPlugin: { parse: vi.fn() } },
  },
}));
vi.mock('youtubei.js/web', () => ({ Constants: {}, Utils: {}, YT: {} }));
vi.mock('../trackLog', () => ({ timed: (_name: string, _id: string, fn: () => unknown) => fn(), trackLog: { d: vi.fn(), e: vi.fn(), i: vi.fn(), w: vi.fn() } }));
vi.mock('./botguard', () => ({ botguard: { init: vi.fn(async () => {}), mint: vi.fn() } }));
vi.mock('@/extension/useYtAuth', () => ({ ytAuthSnapshot: () => ({ signedIn: false }) }));
vi.mock('./innertube', () => ({ getInnertube: vi.fn(async () => ({})), getSignedInnertube: vi.fn() }));
vi.mock('./resolver', () => ({
  CLIENT_ATTEMPTS: 3,
  usesWebPo: vi.fn(),
  excludeClient: vi.fn(),
  playerRequest: vi.fn(),
  resolvePlayer,
  UnplayableError: class extends Error { ageGated = false; },
}));
vi.mock('./ShakaPlayerAdapter', () => ({ ShakaPlayerAdapter: class {} }));

import { SupersededError, YouTubeEngine } from './engine';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('YouTubeEngine load supersession', () => {
  afterEach(() => {
    pending.clear();
    vi.clearAllMocks();
  });

  it('does not start an older track after a newer load supersedes it', async () => {
    resolvePlayer.mockImplementation((_yt: unknown, videoId: string) => {
      const request = deferred<unknown>();
      pending.set(videoId, request);
      return request.promise;
    });
    const engine = new YouTubeEngine({} as HTMLAudioElement);
    const startStream = vi.spyOn(engine as never, 'startStream' as never);

    const oldLoad = engine.load('old-track');
    await vi.waitFor(() => expect(pending.has('old-track')).toBe(true));
    const currentLoad = engine.load('current-track');
    await vi.waitFor(() => expect(pending.has('current-track')).toBe(true));

    pending.get('current-track')!.reject(new Error('current resolution failed'));
    await expect(currentLoad).rejects.toThrow('current resolution failed');
    pending.get('old-track')!.resolve({ videoId: 'old-track' });
    await expect(oldLoad).rejects.toBeInstanceOf(SupersededError);
    expect(startStream).not.toHaveBeenCalled();
  });
});
