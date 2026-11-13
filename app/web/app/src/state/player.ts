import type { DetectedExtension } from '@nuvx/shared/bridge';
import { create } from 'zustand';
import type { Track } from '@/data/music/types';
import { extensionQueryKey } from '@/extension/useExtension';
import { artworkAtPixels } from '@/lib/artwork';
import { queryClient } from '@/lib/queryClient';
import { AUTOMIX_BARS } from '@/playback/automix/choices';
import { extendStation } from '@/playback/queue/builder';
import {
  applyJump,
  asEntry,
  BACK_RESTARTS_AFTER_SEC,
  buildContextQueue,
  buildOneOffQueue,
  playedUserQueueIndices,
  sectionedOrder,
  shuffledStartingOrder,
  shuffledUpcomingOrder,
  upcomingUserQueueIndices,
  userQueueInsertionIndex,
  type QueueEntry,
  type QueueSource,
} from '@/playback/queue/timeline';
import { trackLog } from '@/playback/trackLog';
import type { LoadedTrack, StreamStats, YouTubeEngine } from '@/playback/youtube/engine';
import type { AutomixGraph, AutoDJSnapshot } from '@/playback/automix';

export interface NowPlaying {
  videoId: string;
  title: string;
  artist: string;
  artworkUrl: string | null;
  durationSec: number;
  streamInfo: string | null;
  stream: (StreamStats & { client: string }) | null;
}

export type PlayerStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'buffering' | 'error';
export type RepeatMode = 'off' | 'all' | 'one';

export const CROSSFADE_CHOICES = [0, 3, 6, 9, 12] as const;

interface Settings {
  shuffle: boolean;
  repeat: RepeatMode;
  autoplay: boolean;
  volume: number;
  muted: boolean;
  crossfadeSec: number;
  automix: boolean;
  automixBars: number;
  showNerdStats: boolean;
}

interface PlayerState extends Settings {
  settingsSaveError: string | null;
  queue: QueueEntry[];
  index: number;
  nowPlaying: NowPlaying | null;
  status: PlayerStatus;
  error: string | null;
  deck: number;
  automixState: AutoDJSnapshot | null;
  mixing: boolean;

  playTracks: (tracks: Track[], startIndex: number, source: QueueSource, sourceIndices?: number[]) => void;
  playTrack: (track: Track, source: QueueSource) => void;
  playVideo: (videoId: string, hint?: Partial<Omit<NowPlaying, 'videoId'>>) => Promise<void>;
  playNext: (tracks: Track[], source?: QueueSource | null) => void;
  addToQueue: (tracks: Track[], source?: QueueSource | null) => void;
  jumpTo: (index: number) => void;
  removeAt: (index: number) => void;
  move: (from: number, to: number) => void;
  clearUpNext: () => void;
  next: () => void;
  previous: () => void;
  togglePlay: () => void;
  seek: (seconds: number) => void;
  toggleShuffle: () => void;
  cycleRepeat: () => void;
  setAutoplay: (on: boolean) => void;
  setVolume: (volume: number) => void;
  toggleMute: () => void;
  setCrossfade: (seconds: number) => void;
  setAutomix: (on: boolean) => void;
  setAutomixBars: (bars: number) => void;
  setShowNerdStats: (on: boolean) => void;
}

const SETTINGS_KEY = 'nuvx:player';
const DEFAULT_SETTINGS: Settings = {
  shuffle: false,
  repeat: 'off',
  autoplay: true,
  volume: 1,
  muted: false,
  crossfadeSec: 0,
  automix: false,
  automixBars: 16,
  showNerdStats: false,
};

function loadSettings(): Settings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as Partial<Record<keyof Settings, unknown>>;
    return {
      shuffle: typeof saved.shuffle === 'boolean' ? saved.shuffle : DEFAULT_SETTINGS.shuffle,
      repeat: saved.repeat === 'all' || saved.repeat === 'one' || saved.repeat === 'off' ? saved.repeat : DEFAULT_SETTINGS.repeat,
      autoplay: typeof saved.autoplay === 'boolean' ? saved.autoplay : DEFAULT_SETTINGS.autoplay,
      volume: typeof saved.volume === 'number' && Number.isFinite(saved.volume) && saved.volume >= 0 && saved.volume <= 1
        ? saved.volume : DEFAULT_SETTINGS.volume,
      muted: typeof saved.muted === 'boolean' ? saved.muted : DEFAULT_SETTINGS.muted,
      crossfadeSec: CROSSFADE_CHOICES.includes(saved.crossfadeSec as (typeof CROSSFADE_CHOICES)[number])
        ? saved.crossfadeSec as number : DEFAULT_SETTINGS.crossfadeSec,
      automix: typeof saved.automix === 'boolean' ? saved.automix : DEFAULT_SETTINGS.automix,
      automixBars: AUTOMIX_BARS.includes(saved.automixBars as (typeof AUTOMIX_BARS)[number])
        ? saved.automixBars as number : DEFAULT_SETTINGS.automixBars,
      showNerdStats: typeof saved.showNerdStats === 'boolean' ? saved.showNerdStats : DEFAULT_SETTINGS.showNerdStats,
    };
  } catch {
    return DEFAULT_SETTINGS;
  }
}

function saveSettings(state: Settings) {
  const { shuffle, repeat, autoplay, volume, muted, crossfadeSec, automix, automixBars, showNerdStats } = state;
  try {
    localStorage.setItem(
      SETTINGS_KEY,
      JSON.stringify({ shuffle, repeat, autoplay, volume, muted, crossfadeSec, automix, automixBars, showNerdStats }),
    );
    usePlayer.setState({ settingsSaveError: null });
  } catch (error) {
    usePlayer.setState({ settingsSaveError: 'Player settings could not be saved. They are still active until you close this tab.' });
    trackLog.w(`player settings could not be saved: ${String(error)}`);
  }
}

interface Deck {
  engine: YouTubeEngine;
  audio: HTMLAudioElement;
  gain: number;
  radio: boolean;
}

let decks: Deck[] | null = null;
let decksPromise: Promise<Deck[]> | null = null;
let active = 0;

export function getAudio(): HTMLAudioElement | null {
  return decks?.[active]?.audio ?? null;
}

const boundAudio = new WeakSet<HTMLAudioElement>();

export function getPlayhead(): { sec: number; paused: boolean } | null {
  const audio = getAudio();
  const { mixing, nowPlaying } = usePlayer.getState();
  if (mixing && graph && nowPlaying) {
    const sec = graph.dj.mixPlayhead(nowPlaying.videoId);
    if (sec !== null) return { sec, paused: false };
  }
  return audio ? { sec: audio.currentTime, paused: audio.paused } : null;
}

export function getMixBeat(): { phase: number | null } | null {
  return graph?.dj.mixBeat() ?? null;
}

function getDecks(): Promise<Deck[]> {
  decksPromise ??= (async () => {
    const { YouTubeEngine } = await import('@/playback/youtube/engine');
    decks = [0, 1].map((n) => {
      const audio = new Audio();
      audio.preload = 'auto';
      audio.crossOrigin = 'anonymous';
      const engine = new YouTubeEngine(audio);
      const deck: Deck = { engine, audio, gain: 1, radio: false };
      engine.onFatal = (videoId, message) => {
        if (decks?.[active] !== deck) return;
        trackLog.e(`stopped: ${message}`, videoId);
        usePlayer.setState({ status: 'error', error: message });
      };
      engine.onRecovered = (track) => {
        if (decks?.[active] !== deck) return;
        const { queue, index } = usePlayer.getState();
        const entry = queue[index];
        applyTrack(track, entry?.track.videoId === track.videoId ? entry.track : undefined);
      };
      bindElement(deck, n);
      return deck;
    });
    applyVolumes();
    if (import.meta.env.DEV) Object.defineProperty(window, '__bcAudio', { get: getAudio, configurable: true });
    return decks;
  })();
  return decksPromise;
}

function applyVolumes() {
  const { volume, muted } = usePlayer.getState();
  const level = muted ? 0 : Math.min(1, Math.max(0, volume));
  if (graph) {
    const now = graph.ctx.currentTime;
    graph.master.gain.setTargetAtTime(level, now, 0.015);
    (decks ?? []).forEach((deck, n) => {
      deck.audio.volume = deck.radio ? level * deck.gain : 1;
      graph!.decks[n]!.fader.gain.setTargetAtTime(deck.gain, now, 0.015);
    });
    return;
  }
  for (const deck of decks ?? []) deck.audio.volume = level * deck.gain;
}

let graph: AutomixGraph | null = null;
let graphPromise: Promise<AutomixGraph | null> | null = null;
let mixLoaded: { videoId: string; track: LoadedTrack } | null = null;
let holding = false;

function ensureAutomix(): Promise<AutomixGraph | null> {
  const all = decks;
  if (!all) return Promise.resolve(null);
  graphPromise ??= import('@/playback/automix').then(({ createAutomixGraph, analyzer }) => {
    const built = createAutomixGraph(
      all.map((d) => d.audio),
      (routed) => ({
        front: () => routed[active]!,
        back: () => routed[1 - active]!,
        current: () => {
          if (partyHooks?.inParty()) return null;
          const { queue, index, status } = usePlayer.getState();
          const entry = queue[index];
          return entry && status === 'playing' ? queued(entry) : null;
        },
        next: () => {
          const plan = planNext(true);
          return plan?.kind === 'index' ? queued(plan.queue[plan.index]!) : null;
        },
        loadBack: async (videoId) => {
          const back = all[1 - active]!;
          back.gain = 1;
          applyVolumes();
          try {
            const track = await back.engine.load(videoId, { autoplay: false });
            mixLoaded = { videoId, track };
            return true;
          } catch (error) {
            if (!(error instanceof Error && error.name === 'SupersededError')) {
              trackLog.w(`automix couldn't load the next track: ${String(error)}`, videoId);
            }
            return false;
          }
        },
        handoff: automixHandoff,
        sungSpans,
        hold: (on) => {
          holding = on;
          usePlayer.setState(on ? { status: 'playing', mixing: true } : { mixing: false });
        },
        finished: () => {
          void all[1 - active]!.engine.stop().catch(() => {});
        },
      }),
      (snap) => usePlayer.setState({ automixState: snap }),
    );
    graph = built;
    applyVolumes();
    if (import.meta.env.DEV) Object.assign(window, { __bcAutomix: built, __bcAnalyzer: analyzer });
    return built;
  });
  return graphPromise;
}

function queued(entry: QueueEntry) {
  return { videoId: entry.track.videoId, title: entry.track.title, artist: artistNames(entry.track) };
}

const LYRICS_WAIT_MS = 8000;

async function sungSpans(videoId: string) {
  const { queue } = usePlayer.getState();
  const track = queue.find((e) => e.track.videoId === videoId)?.track;
  if (!track) return null;
  const [{ lookupLyrics }, { endMs, hasKnownEnd, isGap }, { sungSpansFromLines }] = await Promise.all([
    import('@/lyrics/repository'),
    import('@/lyrics/model'),
    import('@/playback/automix'),
  ]);
  const found = await Promise.race([
    lookupLyrics({
      videoId,
      title: track.title,
      artist: artistNames(track),
      durationMs: Math.round((track.durationSec ?? 0) * 1000),
      album: track.album?.name ?? null,
    }).catch(() => null),
    new Promise<null>((r) => setTimeout(() => r(null), LYRICS_WAIT_MS)),
  ]);
  if (!found?.synced) return null;
  return sungSpansFromLines(
    found.lines.map((l) => ({ timeMs: l.timeMs, endMs: hasKnownEnd(l) ? endMs(l) : null, blank: isGap(l) })),
  );
}

function automixHandoff() {
  if (!decks) return;
  const loaded = mixLoaded;
  mixLoaded = null;
  const plan = planNext(true);
  const entry = plan?.kind === 'index' ? plan.queue[plan.index] : undefined;
  if (!plan || plan.kind !== 'index' || !entry || !loaded || entry.track.videoId !== loaded.videoId) {
    trackLog.w('automix handoff found the queue moved; playing on', loaded?.videoId ?? null);
    graph?.dj.cancel();
    return;
  }
  active = 1 - active;
  usePlayer.setState((s) => ({ queue: plan.queue, index: plan.index, deck: s.deck + 1, status: 'playing' }));
  applyTrack(loaded.track, entry.track);
  afterTrackStarted();
  intent();
}

function syncAutomix() {
  if (!usePlayer.getState().automix || usePlayer.getState().queue[usePlayer.getState().index]?.track.streamUrl) {
    graph?.dj.stop();
    return;
  }
  void ensureAutomix().then((g) => {
    const { automix, automixBars } = usePlayer.getState();
    if (!g || !automix) return;
    void g.ctx.resume();
    g.dj.transitionBars = automixBars;
    g.dj.start();
  });
}

function extensionReady(): boolean {
  const detected = queryClient.getQueryData<DetectedExtension | null>(extensionQueryKey);
  return !!detected?.info.capabilities.includes('yt');
}

const artistNames = (track: Track) => track.artists.map((a) => a.name).join(', ');

function hintOf(track: Track): NowPlaying {
  return {
    videoId: track.videoId,
    title: track.title,
    artist: artistNames(track),
    artworkUrl: track.thumbnail,
    durationSec: track.durationSec ?? 0,
    streamInfo: null,
    stream: null,
  };
}

export interface PartyHooks {
  onIntent(): void;
  shouldDeferPlay(): boolean;
  onLockedTransport(playing: boolean): boolean;
  inParty(): boolean;
}

let partyHooks: PartyHooks | null = null;
export function setPartyHooks(hooks: PartyHooks | null): void {
  partyHooks = hooks;
}
const intent = () => partyHooks?.onIntent();

let loadToken = 0;
let canonical: Map<string, number> | null = null;

export const usePlayer = create<PlayerState>()((set, get) => ({
  ...loadSettings(),
  settingsSaveError: null,
  queue: [],
  index: -1,
  nowPlaying: null,
  status: 'idle',
  error: null,
  deck: 0,
  automixState: null,
  mixing: false,

  playTracks(tracks, startIndex, source, sourceIndices) {
    if (!tracks.length) return;
    const { queue, index, shuffle } = get();
    const built = buildContextQueue(queue, index, tracks, startIndex, source, sourceIndices);
    if (shuffle) {
      canonical = positions(built.timeline);
      set({ queue: shuffledStartingOrder(built.timeline, built.startIndex) });
      void startAt(0);
    } else {
      canonical = null;
      set({ queue: built.timeline });
      void startAt(built.startIndex);
    }
    intent();
  },

  playTrack(track, source) {
    const { queue, index } = get();
    canonical = null;
    set({ queue: buildOneOffQueue(queue, index, track, source) });
    void startAt(0);
    intent();
  },

  async playVideo(videoId, hint = {}) {
    get().playTrack(
      {
        videoId,
        title: hint.title ?? '',
        artists: hint.artist ? [{ name: hint.artist, id: null }] : [],
        album: null,
        durationSec: hint.durationSec ?? null,
        explicit: false,
        thumbnail: hint.artworkUrl ?? null,
        index: null,
        plays: null,
      },
      { title: 'Debug', type: 'other' },
    );
  },

  playNext(tracks, source = null) {
    insertUser(tracks, source, true);
    intent();
  },

  addToQueue(tracks, source = null) {
    insertUser(tracks, source, false);
    intent();
  },

  jumpTo(target) {
    const { queue, index } = get();
    if (target === index) return;
    const jumped = applyJump(queue, index, target);
    if (!jumped) return;
    set({ queue: jumped.timeline });
    void startAt(jumped.index);
    intent();
  },

  removeAt(at) {
    const { queue, index } = get();
    if (at === index || at < 0 || at >= queue.length) return;
    set({ queue: queue.filter((_, i) => i !== at), index: at < index ? index - 1 : index });
    void refillAutoplay();
    intent();
  },

  move(from, to) {
    const { queue, index } = get();
    if (from <= index || to <= index || from >= queue.length || to >= queue.length || from === to) return;
    const next = [...queue];
    const [entry] = next.splice(from, 1);
    next.splice(to, 0, entry!);
    set({ queue: next });
    intent();
  },

  clearUpNext() {
    const { queue, index } = get();
    const drop = new Set(upcomingUserQueueIndices(queue.map((e) => e.tier), index));
    set({ queue: queue.filter((_, i) => !drop.has(i)) });
    intent();
  },

  next() {
    void advance(false).then(intent);
  },

  previous() {
    const { index, queue, repeat } = get();
    const audio = getAudio();
    if (audio && audio.currentTime > BACK_RESTARTS_AFTER_SEC) return get().seek(0);
    if (index > 0) void startAt(index - 1);
    else if (repeat === 'all' && queue.length > 1) void startAt(queue.length - 1);
    else return get().seek(0);
    intent();
  },

  togglePlay() {
    if (graph?.dj.loopMixing) cancelCrossfade();
    const audio = getAudio();
    const { nowPlaying, status, index } = get();
    if (!nowPlaying) return;
    const resuming = !audio || audio.paused || status === 'error';
    if (resuming) void graph?.ctx.resume();
    if (partyHooks?.onLockedTransport(resuming)) return;
    if (!audio || status === 'error') {
      void startAt(index, { startSec: audio?.currentTime ?? 0 });
    } else if (audio.paused) {
      if (!partyHooks?.shouldDeferPlay()) void audio.play().catch((e: unknown) => trackLog.w(`play() refused: ${String(e)}`));
    } else {
      cancelCrossfade();
      audio.pause();
    }
    intent();
  },

  seek(seconds) {
    const audio = getAudio();
    if (!audio) return;
    cancelCrossfade();
    audio.currentTime = Math.max(0, Math.min(seconds, audio.duration || seconds));
    updatePositionState();
    intent();
  },

  toggleShuffle() {
    const { queue, index, shuffle } = get();
    const head = queue.slice(0, index + 1);
    const upcoming = queue.slice(index + 1);
    if (!shuffle) {
      canonical = positions(queue);
      const order = shuffledUpcomingOrder(upcoming.map((e) => e.tier));
      set({ shuffle: true, queue: [...head, ...order.map((i) => upcoming[i]!)] });
    } else {
      const known = canonical ?? new Map<string, number>();
      const restored = upcoming
        .map((entry, i) => ({ i, at: known.get(entry.entryId) ?? Number.MAX_SAFE_INTEGER - upcoming.length + i }))
        .sort((a, b) => a.at - b.at)
        .map((x) => x.i);
      const order = sectionedOrder(restored, upcoming.map((e) => e.tier));
      canonical = null;
      set({ shuffle: false, queue: [...head, ...order.map((i) => upcoming[i]!)] });
    }
    saveSettings(get());
  },

  cycleRepeat() {
    const repeat: RepeatMode = { off: 'all', all: 'one', one: 'off' }[get().repeat] as RepeatMode;
    set({ repeat });
    saveSettings(get());
    if (repeat === 'off') void refillAutoplay(true);
  },

  setAutoplay(autoplay) {
    set({ autoplay });
    saveSettings(get());
    if (autoplay) void refillAutoplay(true);
    else {
      const { queue, index } = get();
      set({ queue: queue.filter((e, i) => i <= index || e.tier !== 'autoplay') });
    }
  },

  setVolume(volume) {
    set({ volume: Math.min(1, Math.max(0, volume)), muted: false });
    applyVolumes();
    saveSettings(get());
  },

  toggleMute() {
    set({ muted: !get().muted });
    applyVolumes();
    saveSettings(get());
  },

  setCrossfade(crossfadeSec) {
    set({ crossfadeSec, automix: false });
    saveSettings(get());
    syncAutomix();
  },

  setAutomix(automix) {
    set({ automix, automixState: automix ? get().automixState : null });
    saveSettings(get());
    syncAutomix();
  },

  setAutomixBars(automixBars) {
    set({ automixBars });
    saveSettings(get());
    if (graph) graph.dj.transitionBars = automixBars;
  },


  setShowNerdStats(showNerdStats) {
    set({ showNerdStats });
    saveSettings(get());
  },
}));

if (import.meta.env.DEV) {
  Object.assign(window, {
    __bcPlayer: usePlayer,
    __automixSelfTest: () => import('@/playback/automix/analysis/selftest').then((m) => m.analysisSelfTest()),
  });
}

function positions(queue: readonly QueueEntry[]): Map<string, number> {
  return new Map(queue.map((e, i) => [e.entryId, i]));
}

function insertUser(tracks: Track[], source: QueueSource | null, isNext: boolean) {
  if (!tracks.length) return;
  const { queue, index } = usePlayer.getState();
  const entries = tracks.map((t) => asEntry(t, 'user', source));
  if (index < 0) {
    usePlayer.setState({ queue: entries });
    void startAt(0);
    return;
  }
  const at = userQueueInsertionIndex(queue, index, isNext);
  usePlayer.setState({ queue: [...queue.slice(0, at), ...entries, ...queue.slice(at)] });
}

async function startAt(at: number, { startSec = 0, auto = false, autoplay = true } = {}): Promise<void> {
  const state = usePlayer.getState();
  const entry = state.queue[at];
  if (!entry) return;
  cancelCrossfade();
  const token = ++loadToken;

  if (!entry.track.streamUrl && !extensionReady()) {
    usePlayer.setState({
      index: at,
      nowPlaying: { ...hintOf(entry.track), title: entry.track.title || 'Can’t play this here yet' },
      status: 'error',
      error: 'YouTube playback needs the nuvx extension.',
    });
    return;
  }

  usePlayer.setState({ index: at, nowPlaying: hintOf(entry.track), status: 'loading', error: null });
  updateMediaSession(usePlayer.getState().nowPlaying);
  try {
    const all = await getDecks();
    const deck = all[active]!;
    all[1 - active]!.engine.stop().catch(() => {});
    if (entry.track.streamUrl) {
      await deck.engine.stop().catch(() => {});
      if (!deck.radio) {
        deck.audio.pause();
        deck.audio = new Audio();
        deck.audio.preload = 'none';
        deck.radio = true;
        bindElement(deck, active);
      }
      deck.audio.src = entry.track.streamUrl;
      deck.audio.load();
      deck.gain = 1;
      applyVolumes();
      await deck.audio.play();
      if (token !== loadToken) return;
      const nowPlaying = { ...hintOf(entry.track), artist: artistNames(entry.track) || 'Live radio', durationSec: 0, streamInfo: 'Live radio' };
      usePlayer.setState({ nowPlaying, status: 'playing', error: null, automixState: null });
      updateMediaSession(nowPlaying);
      graph?.dj.stop();
      afterTrackStarted();
      return;
    }
    if (deck.radio) {
      deck.audio.pause();
      deck.audio.removeAttribute('src');
      deck.audio.load();
      deck.audio = deck.engine.audio;
      deck.radio = false;
      bindElement(deck, active);
    }
    deck.gain = 1;
    applyVolumes();
    const track = await deck.engine.load(entry.track.videoId, { startSec, autoplay });
    if (token !== loadToken) return;
    applyTrack(track, entry.track);
    if (!autoplay) usePlayer.setState({ status: 'paused' });
    afterTrackStarted();
    syncAutomix();
  } catch (error) {
    if (token !== loadToken || (error instanceof Error && error.name === 'SupersededError')) return;
    const message = error instanceof Error ? error.message : String(error);
    usePlayer.setState({ status: 'error', error: message });
    if (auto && planNext(true)) {
      trackLog.w(`skipping: ${message}`, entry.track.videoId);
      setTimeout(() => {
        if (token === loadToken) void advance(true);
      }, 1500);
    }
  }
}

type NextPlan = { kind: 'repeat-one' } | { kind: 'index'; queue: QueueEntry[]; index: number };

function planNext(auto: boolean): NextPlan | null {
  const { queue, index, repeat } = usePlayer.getState();
  if (auto && repeat === 'one') return { kind: 'repeat-one' };
  if (index + 1 < queue.length) return { kind: 'index', queue, index: index + 1 };
  if (repeat !== 'off' && queue.length) {
    const tiers = queue.map((e) => e.tier);
    const played = new Set(playedUserQueueIndices(tiers, index));
    const kept = queue.filter((e, i) => !played.has(i) && (e.tier !== 'user' || i > index));
    return kept.length ? { kind: 'index', queue: kept, index: 0 } : null;
  }
  return null;
}

async function advance(auto: boolean): Promise<void> {
  let plan = planNext(auto);
  if (!plan && usePlayer.getState().autoplay) {
    await refillAutoplay(true);
    plan = planNext(auto);
  }
  if (!plan) {
    if (auto) usePlayer.setState({ status: 'paused' });
    return;
  }
  if (plan.kind === 'repeat-one') {
    const audio = getAudio();
    if (audio) {
      audio.currentTime = 0;
      void audio.play();
    }
    return;
  }
  usePlayer.setState({ queue: plan.queue });
  await startAt(plan.index, { auto });
}

function afterTrackStarted() {
  const { queue, index } = usePlayer.getState();
  const played = new Set(playedUserQueueIndices(queue.map((e) => e.tier), index));
  if (played.size) {
    usePlayer.setState({
      queue: queue.filter((_, i) => !played.has(i)),
      index: index - [...played].filter((i) => i < index).length,
    });
  }
  if (!queue[index]?.track.streamUrl) void refillAutoplay();
}

const MAX_QUEUED_AUTOPLAY = 10;
let refilling: Promise<void> | null = null;
let autoplaySeed: string | null = null;
const exhaustedSeeds = new Set<string>();

function refillAutoplay(force = false, anySeed = false): Promise<void> {
  const { autoplay, repeat, queue, index } = usePlayer.getState();
  if (!autoplay || repeat !== 'off' || index < 0) return Promise.resolve();
  if (partyHooks?.inParty()) return Promise.resolve();
  const needed = MAX_QUEUED_AUTOPLAY - queue.slice(index + 1).filter((e) => e.tier === 'autoplay').length;
  if (needed <= 0) return Promise.resolve();
  const playing = queue[index]?.track;
  if (playing?.streamUrl) return Promise.resolve();
  let seed = playing;
  if (seed && exhaustedSeeds.has(seed.videoId) && anySeed) {
    seed = queue
      .slice(0, index)
      .reverse()
      .find((e) => !exhaustedSeeds.has(e.track.videoId))?.track;
  }
  if (!playing || !seed || exhaustedSeeds.has(seed.videoId)) return Promise.resolve();
  if (!force && autoplaySeed === seed.videoId) return Promise.resolve();
  autoplaySeed = seed.videoId;
  refilling ??= (async () => {
    try {
      const { fetchRadio } = await import('@/data/music/api');
      const { candidates, seedIsVideo } = await fetchRadio(seed.videoId);
      const current = usePlayer.getState();
      const extra = extendStation(
        current.queue.map((e) => e.track),
        candidates,
        needed,
        seedIsVideo,
      );
      if (!extra.length) {
        exhaustedSeeds.add(seed.videoId);
        return;
      }
      const source: QueueSource = { title: `${seed.title} Radio`, type: 'radio', id: `RDAMVM${seed.videoId}` };
      if (current.queue[current.index]?.track.videoId !== playing.videoId || !current.autoplay) {
        autoplaySeed = null;
        return;
      }
      usePlayer.setState({ queue: [...current.queue, ...extra.map((t) => asEntry(t, 'autoplay', source))] });
      trackLog.i(`AutoPlay added ${extra.length} tracks from ${seed.title}'s radio`, seed.videoId);
    } catch (error) {
      trackLog.w(`AutoPlay couldn't load the radio: ${String(error)}`, seed.videoId);
      autoplaySeed = null;
    } finally {
      refilling = null;
    }
  })();
  return refilling;
}

const EMPTY_RETRY_MS = 15_000;
let lastEmptyRetry = 0;

function ensureAutoplay() {
  const { autoplay, repeat, queue, index, status } = usePlayer.getState();
  if (!autoplay || repeat !== 'off' || index < 0 || status !== 'playing' || partyHooks?.inParty()) return;
  if (index + 1 < queue.length || refilling) return;
  const now = Date.now();
  if (now - lastEmptyRetry < EMPTY_RETRY_MS) return;
  lastEmptyRetry = now;
  trackLog.i('AutoPlay is on but nothing is queued; asking for the radio again', queue[index]?.track.videoId ?? null);
  void refillAutoplay(true, true);
}

const FADE_STEP_MS = 40;

let fade: { token: number; frame: number; pending: boolean } | null = null;

function cancelCrossfade() {
  if (graph && (graph.dj.mixing || mixLoaded)) {
    mixLoaded = null;
    graph.dj.cancel();
    if (decks) void decks[1 - active]!.engine.stop().catch(() => {});
  } else graph?.dj.cancel();
  if (!fade) return;
  clearTimeout(fade.frame);
  const wasPending = fade.pending;
  fade = null;
  if (!decks) return;
  void decks[1 - active]!.engine.stop().catch(() => {});
  decks[active]!.gain = 1;
  applyVolumes();
  if (wasPending) loadToken++;
}

function maybeCrossfade() {
  const state = usePlayer.getState();
  if (!decks || fade || state.automix || state.queue[state.index]?.track.streamUrl || !state.crossfadeSec || state.status !== 'playing') return;
  const front = decks[active]!;
  const remaining = front.audio.duration - front.audio.currentTime;
  if (!Number.isFinite(remaining) || remaining > state.crossfadeSec || remaining < 1) return;
  const plan = planNext(true);
  if (!plan || plan.kind !== 'index') return;

  const back = decks[1 - active]!;
  const entry = plan.queue[plan.index]!;
  const token = ++loadToken;
  fade = { token, frame: 0, pending: true };
  back.gain = 0;
  applyVolumes();
  trackLog.i(`crossfading into the next track over ${Math.round(remaining)}s`, entry.track.videoId);

  back.engine
    .load(entry.track.videoId, { autoplay: true })
    .then((track) => {
      if (!fade || fade.token !== token || !decks) return;
      fade.pending = false;
      const from = front;
      const to = back;
      active = 1 - active;
      usePlayer.setState((s) => ({ queue: plan.queue, index: plan.index, deck: s.deck + 1, status: 'playing' }));
      applyTrack(track, entry.track);
      afterTrackStarted();

      const length = Math.max(0.5, Math.min(from.audio.duration - from.audio.currentTime, usePlayer.getState().crossfadeSec)) * 1000;
      const started = performance.now();
      const step = () => {
        if (!fade || fade.token !== token) return;
        const t = Math.min(1, (performance.now() - started) / length);
        from.gain = Math.cos((t * Math.PI) / 2);
        to.gain = Math.sin((t * Math.PI) / 2);
        applyVolumes();
        if (t < 1 && !from.audio.ended) fade.frame = window.setTimeout(step, FADE_STEP_MS);
        else {
          fade = null;
          to.gain = 1;
          from.gain = 1;
          void from.engine.stop().catch(() => {});
          applyVolumes();
        }
      };
      step();
    })
    .catch((error: unknown) => {
      if (!fade || fade.token !== token) return;
      trackLog.w(`crossfade load failed: ${String(error)}; the next track starts when this one ends`, entry.track.videoId);
      fade = null;
      back.gain = 1;
      void back.engine.stop().catch(() => {});
      applyVolumes();
      if (front.audio.ended) void advance(true);
    });
}

function applyTrack(track: LoadedTrack, from?: Track) {
  const nowPlaying: NowPlaying = {
    videoId: track.videoId,
    title: from?.title || track.title,
    artist: (from && artistNames(from)) || track.author.replace(/ - Topic$/, ''),
    artworkUrl: from?.thumbnail ?? track.thumbnails[0]?.url ?? null,
    durationSec: track.durationSec || from?.durationSec || 0,
    streamInfo: `${track.format} via ${track.client}`,
    stream: { ...track.stream, client: track.client },
  };
  usePlayer.setState({ nowPlaying, error: null });
  updateMediaSession(nowPlaying);
  followVideo();
}

function followVideo() {
  const deck = decks?.[active];
  if (!deck) return;
  void import('@/playback/youtube/videoDeck').then((m) => m.followVideo(deck.engine, deck.audio));
}

const endedListeners = new Set<() => void>();
export function onTrackEnded(listener: () => void): () => void {
  endedListeners.add(listener);
  return () => endedListeners.delete(listener);
}

function bindElement(deck: Deck, n: number) {
  const element = deck.audio;
  if (boundAudio.has(element)) return;
  boundAudio.add(element);
  const inFront = () => active === n;
  const set = (status: PlayerStatus) => {
    if (!inFront() || holding) return;
    if (usePlayer.getState().status === 'error' && status !== 'playing') return;
    usePlayer.setState({ status });
  };
  element.addEventListener('playing', () => set('playing'));
  element.addEventListener('pause', () => {
    if (!element.ended) set('paused');
  });
  element.addEventListener('waiting', () => set('buffering'));
  element.addEventListener('error', () => {
    if (!inFront() || holding || !deck.radio) return;
    usePlayer.setState({ status: 'error', error: 'The radio stream failed. Try playing it again.' });
  });
  element.addEventListener('ended', () => {
    if (!inFront() || holding) return;
    endedListeners.forEach((listener) => listener());
    if (fade?.pending) cancelCrossfade();
    if (graph?.dj.forceHandoff()) return;
    void advance(true).then(intent);
  });
  element.addEventListener('timeupdate', () => {
    if (!inFront()) return;
    maybeCrossfade();
    ensureAutoplay();
  });
  for (const event of ['durationchange', 'ratechange', 'seeked'] as const) {
    element.addEventListener(event, () => {
      if (inFront()) updatePositionState();
    });
  }
  element.addEventListener('play', () => {
    if (inFront() && 'mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
  });
  element.addEventListener('pause', () => {
    if (inFront() && 'mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
  });
}

let handlersBound = false;

function updateMediaSession(track: NowPlaying | null) {
  if (!('mediaSession' in navigator)) return;
  const session = navigator.mediaSession;
  if (!track) {
    session.metadata = null;
    return;
  }
  session.metadata = new MediaMetadata({
    title: track.title,
    artist: track.artist,
    artwork: track.artworkUrl
      ? [96, 256, 512].map((px) => ({ src: artworkAtPixels(track.artworkUrl!, px), sizes: `${px}x${px}` }))
      : [],
  });

  if (handlersBound) return;
  handlersBound = true;
  const handle = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
    try {
      session.setActionHandler(action, handler);
    } catch {
    }
  };
  const player = () => usePlayer.getState();
  const sounding = () => graph?.dj.loopMixing || getAudio()?.paused === false;
  handle('play', () => !sounding() && player().togglePlay());
  handle('pause', () => sounding() && player().togglePlay());
  handle('seekto', (details) => {
    if (details.seekTime !== undefined) player().seek(details.seekTime);
  });
  handle('seekbackward', (details) => player().seek((getAudio()?.currentTime ?? 0) - (details.seekOffset ?? 10)));
  handle('seekforward', (details) => player().seek((getAudio()?.currentTime ?? 0) + (details.seekOffset ?? 10)));
  handle('previoustrack', () => player().previous());
  handle('nexttrack', () => player().next());
}

function updatePositionState() {
  const audio = getAudio();
  if (!audio || !('mediaSession' in navigator) || !Number.isFinite(audio.duration) || audio.duration <= 0) return;
  try {
    navigator.mediaSession.setPositionState({
      duration: audio.duration,
      playbackRate: audio.playbackRate,
      position: Math.min(audio.currentTime, audio.duration),
    });
  } catch {
  }
}

const PARTY_SOURCE: QueueSource = { title: 'Listen Together', type: 'queue' };

export const partyPlayer = {
  load(tracks: Track[], tiers: ('user' | 'autoplay')[], index: number, startSec: number, play: boolean): Promise<void> {
    canonical = null;
    usePlayer.setState({ queue: tracks.map((t, i) => asEntry(t, tiers[i] ?? 'user', PARTY_SOURCE)) });
    return startAt(index, { startSec, autoplay: play });
  },
  play(): void {
    const audio = getAudio();
    if (audio?.paused) void audio.play().catch((e: unknown) => trackLog.w(`party play() refused: ${String(e)}`));
  },
  pause(): void {
    const audio = getAudio();
    if (audio && !audio.paused) {
      cancelCrossfade();
      audio.pause();
    }
  },
  seekTo(seconds: number): void {
    const audio = getAudio();
    if (!audio) return;
    cancelCrossfade();
    audio.currentTime = Math.max(0, seconds);
    updatePositionState();
  },
  setUpcoming(tracks: Track[], tiers: ('user' | 'autoplay')[]): void {
    const { queue, index } = usePlayer.getState();
    if (index < 0) return;
    usePlayer.setState({ queue: [...queue.slice(0, index + 1), ...tracks.map((t, i) => asEntry(t, tiers[i] ?? 'user', PARTY_SOURCE))] });
  },
};
