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
  currentTime = 0;
  duration = 180;
  volume = 1;
  paused = true;
  ended = false;
  src = '';
  preload = '';
  crossOrigin = '';
  load() {}
  removeAttribute(name: string) { if (name === 'src') this.src = ''; }
  async play() { this.paused = false; this.ended = false; this.dispatchEvent(new Event('playing')); }
  pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
}

const track = (id: string, streamUrl = `https://radio.example/${id}.mp3`) => ({
  videoId: id, title: id, artists: [{ name: 'Radio', id: null }], album: null, durationSec: null,
  explicit: false, thumbnail: null, index: null, plays: null, streamUrl,
});

describe('player behavior at queue and media boundaries', () => {
  beforeEach(() => {
    vi.stubGlobal('Audio', FakeAudio);
    localStorage.clear();
    engineLoad.mockClear();
    fetchRadio.mockReset();
  });

  afterEach(() => {
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
