import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { execute } = vi.hoisted(() => ({ execute: vi.fn() }));

vi.mock('../trackLog', () => ({ trackLog: { d: vi.fn(), w: vi.fn() } }));
vi.mock('./botguard', () => ({ botguard: { mint: vi.fn() } }));
vi.mock('youtubei.js/web', () => ({
  Utils: { generateRandomString: () => 'test' },
  YT: {
    VideoInfo: class {
      playability_status;
      streaming_data;
      constructor([response]: [{ playability_status?: unknown; streaming_data?: unknown }]) {
        this.playability_status = response.playability_status;
        this.streaming_data = response.streaming_data;
      }
    },
  },
}));

import { clientsFor, onSessionChanged, resolvePlayer, unplayableReason, UnplayableError } from './resolver';

const yt = { actions: { execute }, session: { context: { client: {} }, player: {} } } as never;
const unavailable = { playability_status: { status: 'ERROR', reason: 'temporary server failure' } };
const privateTrack = { playability_status: { status: 'ERROR', reason: 'This video is private' } };
const playable = {
  playability_status: { status: 'OK' },
  streaming_data: { adaptive_formats: [{ has_audio: true, has_video: false }] },
};

describe('YouTube player resolver cache', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
    vi.stubGlobal('localStorage', { getItem: () => null });
    execute.mockReset();
    onSessionChanged();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('retries temporarily excluded clients after their exclusion expires', async () => {
    execute.mockResolvedValueOnce(unavailable).mockResolvedValueOnce(unavailable).mockResolvedValueOnce(unavailable).mockResolvedValueOnce(playable);

    await expect(resolvePlayer(yt, 'abcdefghijk')).rejects.toThrow('temporary server failure');
    expect(clientsFor('abcdefghijk')).toEqual(['MWEB']);
    await vi.advanceTimersByTimeAsync(10 * 60_000 + 1);

    await expect(resolvePlayer(yt, 'abcdefghijk')).resolves.toMatchObject({ videoId: 'abcdefghijk', client: 'VISIONOS' });
    expect(execute).toHaveBeenCalledTimes(4);
  });

  it('reuses permanent verdicts until expiry and clears them when the session changes', async () => {
    execute.mockResolvedValue(privateTrack);

    await expect(resolvePlayer(yt, 'abcdefghijk')).rejects.toBeInstanceOf(UnplayableError);
    expect(unplayableReason('abcdefghijk')).toBe('This track is private');
    await expect(resolvePlayer(yt, 'abcdefghijk')).rejects.toThrow('This track is private');
    expect(execute).toHaveBeenCalledTimes(3);

    await vi.advanceTimersByTimeAsync(10 * 60_000 + 1);
    execute.mockResolvedValue(playable);
    await expect(resolvePlayer(yt, 'abcdefghijk')).resolves.toMatchObject({ videoId: 'abcdefghijk' });
    expect(unplayableReason('abcdefghijk')).toBeNull();

    execute.mockResolvedValue(privateTrack);
    await expect(resolvePlayer(yt, 'lmnopqrstuv')).rejects.toBeInstanceOf(UnplayableError);
    onSessionChanged();
    execute.mockResolvedValue(playable);
    await expect(resolvePlayer(yt, 'lmnopqrstuv')).resolves.toMatchObject({ videoId: 'lmnopqrstuv' });
    expect(unplayableReason('lmnopqrstuv')).toBeNull();
  });

  it('keeps signed-in and anonymous verdicts and exclusions separate', async () => {
    execute.mockResolvedValue(privateTrack);
    await expect(resolvePlayer(yt, 'abcdefghijk')).rejects.toBeInstanceOf(UnplayableError);
    expect(clientsFor('abcdefghijk', true)).toContain('YTMUSIC');
    expect(unplayableReason('abcdefghijk', true)).toBeNull();

    execute.mockResolvedValue(playable);
    await expect(resolvePlayer(yt, 'abcdefghijk', new Set(), true)).resolves.toMatchObject({ signed: true });
    expect(execute).toHaveBeenCalledTimes(4);

    execute.mockResolvedValue(privateTrack);
    await expect(resolvePlayer(yt, 'lmnopqrstuv', new Set(), true)).rejects.toBeInstanceOf(UnplayableError);
    execute.mockResolvedValue(playable);
    await expect(resolvePlayer(yt, 'lmnopqrstuv')).resolves.toMatchObject({ signed: false });
  });
});
