// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { engineLoad, fetchRadio } = vi.hoisted(() => ({
  engineLoad: vi.fn(async (videoId: string) => ({
    videoId, title: videoId, author: '', durationSec: 180, thumbnails: [], client: 'WEB', format: 'Opus',
    stream: { codec: 'opus', itag: '251', bitrateKbps: 128, sampleRateHz: 48000, channels: 2, mode: 'direct' }, loudnessDb: null,
  })),
  fetchRadio: vi.fn(),
}));

vi.mock('@/lib/artwork', () => ({ artworkAtPixels: (url: string | null) => url }));
vi.mock('@/lib/queryClient', () => ({ queryClient: { getQueryData: () => ({ info: { capabilities: ['yt'] } }), invalidateQueries: vi.fn() } }));
vi.mock('@/extension/useExtension', () => ({ extensionQueryKey: ['extension'] }));
vi.mock('@/playback/trackLog', () => ({ trackLog: { d: vi.fn(), e: vi.fn(), i: vi.fn(), w: vi.fn() } }));
vi.mock('@/playback/youtube/engine', () => ({
  YouTubeEngine: class {
    audio;
    onFatal = () => {};
    onRecovered = () => {};
    constructor(audio: unknown) { this.audio = audio; }
    load = engineLoad;
    stop = vi.fn(async () => {});
  },
}));
vi.mock('@/data/music/api', () => ({ fetchRadio }));
vi.mock('@/playback/youtube/videoDeck', () => ({ followVideo: vi.fn() }));

class FakeAudio extends EventTarget {
  static instances: FakeAudio[] = [];
  currentTime = 0;
  duration = 180;
  volume = 1;
  paused = true;
  ended = false;
  src = '';
  preload = '';
  crossOrigin = '';
  constructor() { super(); FakeAudio.instances.push(this); }
  load() {}
  removeAttribute(name: string) { if (name === 'src') this.src = ''; }
  async play() { this.paused = false; this.ended = false; this.dispatchEvent(new Event('playing')); }
  pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
}

const track = (id: string, streamUrl = `https://radio.example/${id}.mp3`) => ({
  videoId: id, title: id, artists: [{ name: 'Radio', id: null }], album: null, durationSec: null,
  explicit: false, thumbnail: null, index: null, plays: null, streamUrl,
});
const loaded = (videoId: string) => ({
  videoId, title: videoId, author: '', durationSec: 180, thumbnails: [], client: 'WEB', format: 'Opus',
  stream: { codec: 'opus', itag: '251', bitrateKbps: 128, sampleRateHz: 48000, channels: 2, mode: 'direct' as const }, loudnessDb: null,
});

describe('player behavior at queue and media boundaries', () => {
  beforeEach(() => {
    vi.stubGlobal('Audio', FakeAudio);
    FakeAudio.instances = [];
    localStorage.clear();
    engineLoad.mockReset();
    engineLoad.mockImplementation(async (videoId) => loaded(videoId));
    fetchRadio.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(navigator, 'mediaSession');
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  async function radioPlayer() {
    const player = await import('./player');
    player.usePlayer.getState().playTrack(track('first'), { title: 'Radio', type: 'radio' });
    await vi.waitFor(() => expect(player.usePlayer.getState().status).toBe('playing'));
    return player;
  }

  it('keeps durationless radio seek and ended-track progression finite', async () => {
    const player = await radioPlayer();
    const second = { ...track('second'), streamUrl: 'https://radio.example/second.mp3' };
    player.usePlayer.setState({ queue: [player.usePlayer.getState().queue[0]!, { ...player.usePlayer.getState().queue[0]!, track: second }], index: 0 });

    player.usePlayer.getState().seek(42);
    expect(player.getPlayhead()?.sec).toBe(42);
    expect(player.usePlayer.getState().nowPlaying?.durationSec).toBe(0);
    const audio = player.getAudio() as FakeAudio;
    audio.ended = true;
    audio.dispatchEvent(new Event('ended'));
    await vi.waitFor(() => expect(player.usePlayer.getState().index).toBe(1));
    expect(player.usePlayer.getState().nowPlaying?.videoId).toBe('second');
  });

  it('clamps seeks at the beginning and finite duration, and accepts finite live seeks', async () => {
    const player = await radioPlayer();
    const audio = player.getAudio() as FakeAudio;

    player.usePlayer.getState().seek(-5);
    expect(audio.currentTime).toBe(0);
    player.usePlayer.getState().seek(180);
    expect(audio.currentTime).toBe(180);
    player.usePlayer.getState().seek(240);
    expect(audio.currentTime).toBe(180);
    audio.duration = Number.POSITIVE_INFINITY;
    player.usePlayer.getState().seek(25);
    expect(audio.currentTime).toBe(25);
    expect(Number.isFinite(audio.currentTime)).toBe(true);
  });

  it.each([9.99, 10])('goes to the previous track at %s seconds', async (position) => {
    const player = await radioPlayer();
    const current = player.usePlayer.getState().queue[0]!;
    player.usePlayer.setState({ queue: [{ ...current, track: track('previous') }, { ...current, track: track('current') }], index: 1 });
    (player.getAudio() as FakeAudio).currentTime = position;

    player.usePlayer.getState().previous();

    await vi.waitFor(() => expect(player.usePlayer.getState().nowPlaying?.videoId).toBe('previous'));
  });

  it('restarts the current track only after the previous-button threshold', async () => {
    const player = await radioPlayer();
    const current = player.usePlayer.getState().queue[0]!;
    player.usePlayer.setState({ queue: [{ ...current, track: track('previous') }, { ...current, track: track('current') }], index: 1 });
    (player.getAudio() as FakeAudio).currentTime = 10.01;

    player.usePlayer.getState().previous();

    expect(player.usePlayer.getState().index).toBe(1);
    expect((player.getAudio() as FakeAudio).currentTime).toBe(0);
  });

  it('restores radio playback after a stream error is retried', async () => {
    const player = await radioPlayer();
    const audio = player.getAudio() as FakeAudio;
    audio.dispatchEvent(new Event('error'));
    expect(player.usePlayer.getState()).toMatchObject({ status: 'error', error: expect.stringContaining('radio stream failed') });

    player.usePlayer.getState().togglePlay();

    await vi.waitFor(() => expect(player.usePlayer.getState()).toMatchObject({ status: 'playing', error: null }));
    expect(audio.src).toBe('https://radio.example/first.mp3');
  });

  it('keeps settings changes active when storage writes fail', async () => {
    const player = await radioPlayer();
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota exceeded'); });

    expect(() => player.usePlayer.getState().setVolume(0.7)).not.toThrow();
    expect(player.usePlayer.getState()).toMatchObject({ volume: 0.7, settingsSaveError: expect.stringContaining('could not be saved') });
    write.mockRestore();
    player.usePlayer.getState().setVolume(0.8);
    expect(player.usePlayer.getState().settingsSaveError).toBeNull();
  });

  it('bounds volume and applies mute changes to both audio decks', async () => {
    const player = await radioPlayer();
    player.usePlayer.getState().setVolume(-4);
    expect(player.usePlayer.getState().volume).toBe(0);
    player.usePlayer.getState().setVolume(4);
    expect(player.usePlayer.getState().volume).toBe(1);

    player.usePlayer.getState().setVolume(0.6);
    const deckAudio = FakeAudio.instances.slice(-2);
    expect(deckAudio.map((audio) => audio.volume)).toEqual([0.6, 0.6]);
    player.usePlayer.getState().toggleMute();
    expect(deckAudio.map((audio) => audio.volume)).toEqual([0, 0]);
  });

  it('rejects persisted out-of-range volume and clamps rapid updates', async () => {
    localStorage.setItem('nuvx:player', JSON.stringify({ volume: 2 }));
    const player = await import('./player');
    expect(player.usePlayer.getState().volume).toBe(1);

    for (const value of [-1, 0.2, 1.5, 0.42]) player.usePlayer.getState().setVolume(value);
    expect(player.usePlayer.getState().volume).toBe(0.42);
  });

  it('round trips the existing player settings record without changing its shape', async () => {
    const saved = {
      shuffle: true,
      repeat: 'one',
      autoplay: false,
      volume: 0.4,
      muted: true,
      crossfadeSec: 6,
      automix: true,
      automixBars: 32,
      showNerdStats: true,
    };
    localStorage.setItem('nuvx:player', JSON.stringify(saved));
    const player = await import('./player');

    expect(player.usePlayer.getState()).toMatchObject(saved);
    player.usePlayer.getState().setShowNerdStats(false);

    expect(JSON.parse(localStorage.getItem('nuvx:player')!)).toEqual({ ...saved, showNerdStats: false });
  });

  it('replaces stale media metadata and keeps actions safe with an empty queue', async () => {
    const handlers = new Map<string, (details?: { seekTime?: number }) => void>();
    const session = {
      metadata: null as unknown,
      playbackState: 'none',
      setActionHandler: vi.fn((action: string, handler: ((details?: { seekTime?: number }) => void) | null) => {
        if (handler) handlers.set(action, handler);
      }),
    };
    Object.defineProperty(navigator, 'mediaSession', { configurable: true, value: session });
    vi.stubGlobal('MediaMetadata', class { constructor(readonly init: Record<string, unknown>) { Object.assign(this, init); } });
    const player = await radioPlayer();
    player.usePlayer.getState().playTrack({ ...track('artwork'), thumbnail: 'https://img.example/cover.jpg' }, { title: 'Radio', type: 'radio' });
    await vi.waitFor(() => expect(player.usePlayer.getState().nowPlaying?.videoId).toBe('artwork'));
    expect((session.metadata as { artwork: unknown[] }).artwork).toHaveLength(3);

    player.usePlayer.getState().playTrack({ ...track('plain'), thumbnail: null }, { title: 'Radio', type: 'radio' });
    await vi.waitFor(() => expect(player.usePlayer.getState().nowPlaying?.videoId).toBe('plain'));
    expect((session.metadata as { title: string; artwork: unknown[] }).title).toBe('plain');
    expect((session.metadata as { artwork: unknown[] }).artwork).toEqual([]);

    player.usePlayer.setState({ queue: [], index: -1, nowPlaying: null, status: 'idle' });
    expect(() => handlers.get('previoustrack')?.()).not.toThrow();
    expect(() => handlers.get('nexttrack')?.()).not.toThrow();
    expect(() => handlers.get('seekto')?.({ seekTime: 20 })).not.toThrow();
  });

  it('cancels a pending crossfade when playback is paused', async () => {
    const player = await youtubePlayer();
    const current = player.usePlayer.getState().queue[0]!;
    player.usePlayer.setState({ queue: [current, { ...current, entryId: 'next', track: track('next', '') }], index: 0, crossfadeSec: 6, status: 'playing' });
    let resolveIncoming!: (value: Awaited<ReturnType<typeof engineLoad>>) => void;
    engineLoad.mockReturnValueOnce(new Promise((resolve) => { resolveIncoming = resolve; }));
    const audio = player.getAudio() as FakeAudio;
    audio.paused = false;
    audio.currentTime = 175;
    audio.dispatchEvent(new Event('timeupdate'));
    await vi.waitFor(() => expect(engineLoad).toHaveBeenCalledTimes(2));

    player.usePlayer.getState().togglePlay();
    resolveIncoming(loaded('next'));
    await Promise.resolve();

    expect(player.usePlayer.getState()).toMatchObject({ index: 0, status: 'paused', nowPlaying: { videoId: 'one' } });
  });

  it('discards stale track loads after rapid track changes', async () => {
    const player = await import('./player');
    fetchRadio.mockResolvedValue({ candidates: [], seedIsVideo: false });
    const pending = new Map<string, (value: ReturnType<typeof loaded>) => void>();
    engineLoad.mockImplementation((videoId) => new Promise((resolve) => { pending.set(videoId, resolve); }));
    player.usePlayer.getState().playTrack(track('old', ''), { title: 'Queue', type: 'search' });
    await vi.waitFor(() => expect(pending.has('old')).toBe(true));
    player.usePlayer.getState().playTrack(track('new', ''), { title: 'Queue', type: 'search' });
    await vi.waitFor(() => expect(pending.has('new')).toBe(true));

    pending.get('new')!(loaded('new'));
    await vi.waitFor(() => expect(player.usePlayer.getState().nowPlaying?.streamInfo).toBe('Opus via WEB'));
    player.usePlayer.setState({ status: 'playing' });
    pending.get('old')!(loaded('old'));
    await Promise.resolve();

    expect(player.usePlayer.getState()).toMatchObject({ status: 'playing', nowPlaying: { videoId: 'new' } });
  });

  it('wraps a repeat-all queue and restarts the current item on repeat-one ended', async () => {
    const player = await radioPlayer();
    const first = player.usePlayer.getState().queue[0]!;
    const second = { ...first, entryId: 'same-video-again', track: track('first') };
    player.usePlayer.setState({ queue: [first, second], index: 1, repeat: 'all' });
    player.usePlayer.getState().next();
    await vi.waitFor(() => expect(player.usePlayer.getState().index).toBe(0));
    expect(player.usePlayer.getState().queue.map((row) => row.track.videoId)).toEqual(['first', 'first']);

    player.usePlayer.setState({ queue: [first], index: 0, repeat: 'one' });
    const audio = player.getAudio() as FakeAudio;
    audio.currentTime = 34;
    audio.ended = true;
    audio.dispatchEvent(new Event('ended'));
    await vi.waitFor(() => expect(audio.currentTime).toBe(0));
    expect(player.usePlayer.getState().index).toBe(0);

    player.usePlayer.setState({ queue: [first, second], index: 1, repeat: 'off' });
    player.usePlayer.getState().next();
    await Promise.resolve();
    expect(player.usePlayer.getState().index).toBe(1);
  });

  it('keeps the current queue row fixed while moving upcoming rows in either direction', async () => {
    const player = await radioPlayer();
    const current = player.usePlayer.getState().queue[0]!;
    const rows = ['duplicate', 'b', 'duplicate', 'd'].map((id, i) => ({ ...current, entryId: `row-${i}`, track: track(id) }));
    player.usePlayer.setState({ queue: [current, ...rows], index: 0 });

    player.usePlayer.getState().move(1, 3);
    expect(player.usePlayer.getState().queue.map((row) => row.entryId)).toEqual([current.entryId, 'row-1', 'row-2', 'row-0', 'row-3']);
    player.usePlayer.getState().move(4, 1);
    expect(player.usePlayer.getState().queue.map((row) => row.entryId)).toEqual([current.entryId, 'row-3', 'row-1', 'row-2', 'row-0']);
    expect(player.usePlayer.getState().queue[player.usePlayer.getState().index]?.entryId).toBe(current.entryId);

    player.usePlayer.getState().removeAt(3);
    expect(player.usePlayer.getState().queue.some((row) => row.entryId === 'row-2')).toBe(false);
    expect(player.usePlayer.getState().queue.some((row) => row.entryId === 'row-0')).toBe(true);
  });

  it('preserves played queue history when shuffle is edited and restored', async () => {
    const player = await import('./player');
    const entry = (id: string, tier: 'context' | 'user' | 'autoplay') => ({
      entryId: id, tier, track: track(id, ''), source: null,
    });
    const queue = [entry('played', 'user'), entry('current', 'context'), entry('context-a', 'context'), entry('user-a', 'user'), entry('context-b', 'context'), entry('autoplay-a', 'autoplay')];
    player.usePlayer.setState({ queue, index: 1, shuffle: false });

    player.usePlayer.getState().toggleShuffle();
    player.usePlayer.getState().move(2, player.usePlayer.getState().queue.length - 1);
    player.usePlayer.getState().toggleShuffle();

    const restored = player.usePlayer.getState();
    expect(restored.queue.slice(0, 2).map((row) => row.entryId)).toEqual(['played', 'current']);
    expect(restored.queue[restored.index]?.entryId).toBe('current');
    expect(new Set(restored.queue.map((row) => row.entryId)).size).toBe(queue.length);
  });

  it('keeps the upcoming track playable when the incoming crossfade deck fails', async () => {
    const player = await youtubePlayer();
    const current = player.usePlayer.getState().queue[0]!;
    player.usePlayer.setState({
      queue: [current, { ...current, entryId: 'next', track: track('next', '') }],
      index: 0, crossfadeSec: 6, status: 'playing',
    });
    engineLoad.mockRejectedValueOnce(new Error('incoming deck failed'));
    const outgoing = player.getAudio() as FakeAudio;
    outgoing.currentTime = 175;
    outgoing.dispatchEvent(new Event('timeupdate'));
    await vi.waitFor(() => expect(player.usePlayer.getState().index).toBe(0));

    outgoing.ended = true;
    outgoing.dispatchEvent(new Event('ended'));
    await vi.waitFor(() => expect(player.usePlayer.getState().index).toBe(1));
    expect(player.usePlayer.getState().nowPlaying?.videoId).toBe('next');
  });

  it('ignores a late ended event from the outgoing deck after crossfade handoff', async () => {
    const player = await youtubePlayer();
    const current = player.usePlayer.getState().queue[0]!;
    player.usePlayer.setState({
      queue: [current, { ...current, entryId: 'next', track: track('next', '') }],
      index: 0, crossfadeSec: 6, status: 'playing',
    });
    const outgoing = player.getAudio() as FakeAudio;
    const ended = vi.fn();
    player.onTrackEnded(ended);
    outgoing.currentTime = 175;
    outgoing.dispatchEvent(new Event('timeupdate'));
    await vi.waitFor(() => expect(player.usePlayer.getState().index).toBe(1));

    outgoing.ended = true;
    outgoing.dispatchEvent(new Event('ended'));
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(ended).not.toHaveBeenCalled();
    expect(player.usePlayer.getState().index).toBe(1);
  });

  it('shares concurrent autoplay top-ups and deduplicates simultaneous suggestions', async () => {
    const player = await import('./player');
    let resolveRadio!: (result: { candidates: { track: ReturnType<typeof track>; isVideo: boolean }[]; seedIsVideo: boolean }) => void;
    fetchRadio.mockReturnValue(new Promise((resolve) => { resolveRadio = resolve; }));
    player.usePlayer.getState().playTrack(track('seed', ''), { title: 'Queue', type: 'search' });
    await vi.waitFor(() => expect(player.getAudio()).not.toBeNull());
    await vi.waitFor(() => expect(fetchRadio).toHaveBeenCalledOnce());

    player.usePlayer.getState().setAutoplay(true);
    player.usePlayer.getState().setAutoplay(true);
    expect(fetchRadio).toHaveBeenCalledOnce();
    const suggestion = track('suggestion', '');
    resolveRadio({ candidates: [{ track: suggestion, isVideo: false }, { track: suggestion, isVideo: false }], seedIsVideo: false });

    await vi.waitFor(() => expect(player.usePlayer.getState().queue.filter((row) => row.tier === 'autoplay')).toHaveLength(1));
    expect(player.usePlayer.getState().queue.filter((row) => row.track.videoId === 'suggestion')).toHaveLength(1);
  });

  it('does not append an in-flight autoplay result after autoplay is disabled', async () => {
    const player = await import('./player');
    let resolveRadio!: (result: { candidates: { track: ReturnType<typeof track>; isVideo: boolean }[]; seedIsVideo: boolean }) => void;
    fetchRadio.mockReturnValue(new Promise((resolve) => { resolveRadio = resolve; }));
    player.usePlayer.getState().playTrack(track('seed', ''), { title: 'Queue', type: 'search' });
    await vi.waitFor(() => expect(fetchRadio).toHaveBeenCalledOnce());
    player.usePlayer.getState().setAutoplay(false);
    player.usePlayer.getState().clearUpNext();
    resolveRadio({ candidates: [{ track: track('late-suggestion', ''), isVideo: false }], seedIsVideo: false });

    await vi.waitFor(() => expect(player.usePlayer.getState().autoplay).toBe(false));
    await Promise.resolve();
    expect(player.usePlayer.getState().queue.some((row) => row.track.videoId === 'late-suggestion')).toBe(false);
  });

  async function youtubePlayer() {
    const player = await import('./player');
    fetchRadio.mockResolvedValue({ candidates: [], seedIsVideo: false });
    player.usePlayer.getState().playTrack(track('one', ''), { title: 'Queue', type: 'search' });
    await vi.waitFor(() => expect(player.usePlayer.getState().nowPlaying?.videoId).toBe('one'));
    await vi.waitFor(() => expect(player.getAudio()).not.toBeNull());
    player.usePlayer.setState({ status: 'playing' });
    return player;
  }
});
