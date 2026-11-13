// SPDX-License-Identifier: AGPL-3.0-only
import type { TrackAnalysis } from '../analysis/types';
import { analyzer } from '../analysis/analyzer';
import type { Deck } from './deck';
import type { Candidate } from './selector';
import { scheduleTransition, plannedBars, MIN_GRID_CONFIDENCE, type TransitionPlan } from './transition-scheduler';
import { asOutgoing, gridOf, phraseAtOrBefore } from './grid';
import type { LoopDeck } from './loopDeck';
import {
  IN_REGION_SEC,
  mixBeatPhaseAt,
  mixPositionAt,
  OUT_REGION_SEC,
  OUT_SEARCH_SEC,
  planLoopMix,
  scheduleLoopMix,
  type LoopMixPlan,
  type LoopMixTimes,
  type SungSpans,
} from './loopMix';

export type AutoDJState = 'idle' | 'playing' | 'scheduled';

export interface QueuedTrack {
  videoId: string;
  title: string;
  artist: string;
}

export interface AutomixHost {
  ctx: AudioContext;
  front(): Deck;
  back(): Deck;
  current(): QueuedTrack | null;
  next(): QueuedTrack | null;
  loadBack(videoId: string): Promise<boolean>;
  handoff(): void;
  finished(): void;
  loopDecks(): Promise<[LoopDeck, LoopDeck]>;
  sungSpans(videoId: string): Promise<SungSpans | null>;
  hold(on: boolean): void;
}

export interface AutoDJSnapshot {
  state: AutoDJState;
  currentKey: string | null;
  nextKey: string | null;
  nextLoaded: boolean;
  candidates: Candidate[];
  transitionAt: number | null;
  transitionDuration: number | null;
  plan: TransitionPlan | null;
  window: MixWindow | null;
  startsInSec: number | null;
  beatSec: number | null;
  loop: LoopMixPlan | null;
}

export interface MixWindow {
  videoId: string;
  startSec: number;
  endSec: number;
}

const PRELOAD_WINDOW_SEC = 60;
const LOOP_PREPARE_SEC = OUT_SEARCH_SEC + 40;
const LOOP_SETUP_SEC = 15;
const SCHEDULE_WINDOW_SEC = 12;
const EMERGENCY_REMAINING_SEC = 10;

export class AutoDJ {
  transitionBars = 16;

  private state: AutoDJState = 'idle';
  private history: string[] = [];
  private currentKey: string | null = null;
  private nextKey: string | null = null;
  private nextLoadedKey: string | null = null;
  private candidates: Candidate[] = [];
  private plan: TransitionPlan | null = null;
  private blend: { from: Deck; to: Deck; handedOff: boolean } | null = null;
  private settling: Deck | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private ticking = false;
  private loadToken = 0;
  private window: MixWindow | null = null;
  private bothAnalyzed = false;
  private loopPlan: LoopMixPlan | null = null;
  private loopPreparing: string | null = null;
  private loopTimes: LoopMixTimes | null = null;
  private loopDecks: [LoopDeck, LoopDeck] | null = null;
  private beatSec: number | null = null;

  constructor(
    private host: AutomixHost,
    private onUpdate: (snap: AutoDJSnapshot) => void = () => {},
    private onTransition?: (from: TrackAnalysis, to: TrackAnalysis, plan: TransitionPlan, bars: number) => void,
  ) {}

  get snapshot(): AutoDJSnapshot {
    return {
      state: this.state,
      currentKey: this.currentKey,
      nextKey: this.nextKey,
      nextLoaded: this.nextKey !== null && this.nextLoadedKey === this.nextKey,
      candidates: this.candidates.slice(0, 8),
      transitionAt: this.plan?.t0 ?? null,
      transitionDuration: this.plan?.durationSec ?? null,
      plan: this.plan,
      window: this.window,
      startsInSec: this.plan ? this.plan.t0 - this.host.ctx.currentTime : null,
      beatSec: this.beatSec,
      loop: this.loopPlan,
    };
  }

  mixPlayhead(videoId: string): number | null {
    const plan = this.loopPlan;
    const times = this.loopTimes;
    if (!plan || !times) return null;
    const side = videoId === plan.out.key ? 'out' : videoId === plan.in.key ? 'in' : null;
    return side ? mixPositionAt(plan, times, side, this.host.ctx.currentTime) : null;
  }

  mixBeat(): { phase: number | null } | null {
    if (this.state !== 'scheduled' || !this.plan) return null;
    const now = this.host.ctx.currentTime;
    if (this.loopTimes) {
      const phase = mixBeatPhaseAt(this.loopTimes, now);
      return phase === null ? null : { phase };
    }
    const { t0, durationSec } = this.plan;
    if (now < t0 || now > t0 + durationSec) return null;
    const beat = this.beatSec;
    if (!beat) return { phase: null };
    const x = (now - t0) / beat;
    return { phase: x - Math.floor(x) };
  }

  get loopMixing(): boolean {
    return this.state === 'scheduled' && this.loopTimes !== null;
  }

  get mixing(): boolean {
    return this.state === 'scheduled';
  }

  start(): void {
    if (this.state === 'idle') this.state = 'playing';
    this.timer ??= setInterval(() => void this.tick(), 1000);
    void this.tick();
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.cancel();
    this.state = 'idle';
    this.push();
  }

  cancel(): void {
    this.loadToken++;
    if (this.loopTimes && this.blend && this.loopDecks) {
      const [outDeck, inDeck] = this.loopDecks;
      const onIncoming = this.blend.handedOff;
      const keep = onIncoming ? this.blend.to : this.blend.from;
      const drop = onIncoming ? this.blend.from : this.blend.to;
      const at =
        this.mixPlayhead((onIncoming ? this.loopPlan?.in.key : this.loopPlan?.out.key) ?? '') ??
        (onIncoming ? this.loopTimes.elementTrackTime : this.loopPlan?.out.loop[0]) ??
        keep.audio.currentTime;
      outDeck.stop();
      inDeck.stop();
      drop.cancel();
      keep.cancel();
      keep.audio.currentTime = at;
      void keep.audio.play().catch(() => {});
      this.loopTimes = null;
      this.blend = null;
      this.host.hold(false);
    }
    this.loopPlan = null;
    this.loopPreparing = null;
    if (this.blend) {
      this.blend.from.cancel();
      this.blend.to.cancel();
    }
    this.settling?.cancel();
    this.settling = null;
    this.window = null;
    this.blend = null;
    this.plan = null;
    this.nextKey = null;
    this.nextLoadedKey = null;
    this.candidates = [];

    if (this.state === 'scheduled') this.state = 'playing';
    this.push();
  }

  forceHandoff(): boolean {
    if (!this.blend || this.blend.handedOff) return false;
    this.blend.handedOff = true;
    this.host.handoff();
    return true;
  }

  private currentAnalysis(): TrackAnalysis | null {
    return this.currentKey ? analyzer.get(this.currentKey) : null;
  }

  private async tick(): Promise<void> {
    if (this.ticking || this.state === 'idle') return;
    this.ticking = true;
    try {
      const now = this.host.ctx.currentTime;

      if (this.state === 'scheduled' && this.plan && this.blend) {
        const handoffAt = this.loopTimes?.handoffAt ?? this.plan.t0 + this.plan.durationSec / 2;
        if (!this.blend.handedOff && now >= handoffAt) {
          this.blend.handedOff = true;
          this.host.handoff();
        }
        if (now > this.plan.t0 + this.plan.durationSec + 0.3) this.completeTransition();
        this.push();
        return;
      }

      const playing = this.host.current();
      const front = this.host.front();
      if (!playing || !front.isPlaying) return;
      if (playing.videoId !== this.currentKey) {
        this.currentKey = playing.videoId;
        this.nextKey = null;
        this.nextLoadedKey = null;

        this.candidates = [];
        this.window = null;
        this.loopPlan = null;
      }
      front.anchor();
      analyzer.request(playing.videoId, tags(playing), true);

      const queued = this.host.next();
      if (!queued) {
        this.nextKey = null;
        this.window = null;
        this.push();
        return;
      }
      if (queued.videoId !== this.nextKey) {
        this.nextKey = queued.videoId;
        this.nextLoadedKey = null;
        this.loopPlan = null;
        this.loadToken++;
      }
      analyzer.request(queued.videoId, tags(queued), true);
      analyzer.request(playing.videoId, tags(playing), true);

      this.bothAnalyzed = this.currentAnalysis() !== null && analyzer.get(queued.videoId) !== null;
      const cur = asOutgoing(
        this.bothAnalyzed ? this.currentAnalysis()! : stubAnalysis(playing, front.durationSec),
      );
      if (this.bothAnalyzed && !this.loopPlan && this.trackRemaining() < LOOP_PREPARE_SEC) {
        void this.prepareLoopMix(cur, analyzer.get(queued.videoId)!);
      }
      const remaining = this.timeToMixOut(cur);
      if (!this.bothAnalyzed) this.window = null;

      if (this.nextKey && this.nextLoadedKey !== this.nextKey && remaining < PRELOAD_WINDOW_SEC) {
        await this.loadNext(this.nextKey);
      }

      const ready = this.nextKey !== null && this.nextLoadedKey === this.nextKey;
      if (ready && remaining < SCHEDULE_WINDOW_SEC && this.loopPlan) {
        await this.scheduleLoop(cur);
      } else if (ready && remaining < SCHEDULE_WINDOW_SEC) {
        this.scheduleBlend(cur, false);
      } else if (ready && this.trackRemaining() < EMERGENCY_REMAINING_SEC) {
        this.scheduleBlend(cur, true);
      }
      this.push();
    } finally {
      this.ticking = false;
    }
  }

  private timeToMixOut(cur: TrackAnalysis): number {
    const front = this.host.front();
    const now = this.host.ctx.currentTime;
    const pos = front.expectedTrackTime ?? 0;
    if (this.loopPlan && this.loopPlan.out.loop[0] > pos + 1) {
      const [start, end] = this.loopPlan.out.loop;
      this.window = { videoId: cur.fileKey, startSec: start, endSec: end };
      this.beatSec = this.loopPlan.out.beatSec;
      const t0 = front.timeline.ctxTimeAt(start, now);
      return t0 === null ? 0 : Math.max(0, t0 - now);
    }
    if (this.loopPlan) this.loopPlan = null;
    const grid = gridOf(cur);
    if (!grid || cur.gridConfidence < MIN_GRID_CONFIDENCE) {
      const target = (cur.outroStartSec ?? cur.durationSec) - 12;
      this.window = { videoId: cur.fileKey, startSec: Math.max(0, target), endSec: Math.max(0, target) + 12 };
      this.beatSec = null;
      return Math.max(0, target - pos);
    }
    const outRate = front.timeline.rateAt(now) || 1;
    const effBpm = grid.bpm * outRate;
    const next = this.nextKey ? analyzer.get(this.nextKey) : null;
    const bars = plannedBars(this.transitionBars, cur, next ?? null, outRate);
    const blendOutputSec = bars * 4 * (60 / effBpm);
    const blendInputSec = blendOutputSec * outRate;
    let mixOut = phraseAtOrBefore(grid, (cur.outroStartSec ?? cur.durationSec) - blendInputSec);
    mixOut = Math.min(mixOut, phraseAtOrBefore(grid, cur.durationSec - blendInputSec - grid.barSec));
    this.window = { videoId: cur.fileKey, startSec: mixOut, endSec: mixOut + blendInputSec };
    this.beatSec = grid.beatSec / outRate;
    const t0 = front.timeline.ctxTimeAt(mixOut, now);
    return t0 === null ? 0 : Math.max(0, t0 - now);
  }

  private trackRemaining(): number {
    const front = this.host.front();
    const pos = front.expectedTrackTime ?? 0;
    return front.durationSec - pos;
  }

  private async loadNext(videoId: string): Promise<void> {
    const token = ++this.loadToken;
    const ok = await this.host.loadBack(videoId);
    if (token !== this.loadToken) return;
    if (ok) this.nextLoadedKey = videoId;
    else this.nextKey = null;
  }

  private scheduleBlend(cur: TrackAnalysis, forceNow: boolean): void {
    const queued = this.host.next();
    if (!this.nextKey || queued?.videoId !== this.nextKey) return;
    const from = this.host.front();
    const to = this.host.back();
    const next = (this.bothAnalyzed ? analyzer.get(this.nextKey) : null) ?? stubAnalysis(queued, to.durationSec);
    to.lock(from);
    to.setVolume(1);
    to.eq.reset();
    this.plan = scheduleTransition({
      from,
      to,
      fromAnalysis: cur,
      toAnalysis: next,
      bars: this.transitionBars,
      forceNow,
    });
    this.blend = { from, to, handedOff: false };
    const outRate = from.timeline.rateAt(this.host.ctx.currentTime) || 1;
    this.window = {
      videoId: cur.fileKey,
      startSec: this.plan.mixOutTrackTime,
      endSec: this.plan.mixOutTrackTime + this.plan.durationSec * outRate,
    };
    if (!this.bothAnalyzed) this.window = null;
    const grid = this.plan.style === 'crossfade' ? null : gridOf(cur);
    this.beatSec = grid ? grid.beatSec / outRate : null;
    this.state = 'scheduled';
    this.onTransition?.(cur, next, this.plan, this.transitionBars);
  }

  private async prepareLoopMix(cur: TrackAnalysis, next: TrackAnalysis): Promise<void> {
    const key = `${cur.fileKey}>${next.fileKey}`;
    if (this.loopPreparing === key) return;
    this.loopPreparing = key;
    const stillWanted = () => this.loopPreparing === key && this.currentKey === cur.fileKey && this.nextKey === next.fileKey;
    try {
      const outGrid = asOutgoing(cur);
      if (outGrid.gridConfidence < MIN_GRID_CONFIDENCE || next.gridConfidence < MIN_GRID_CONFIDENCE) return;
      this.loopDecks ??= await this.host.loopDecks();
      const ctx = this.host.ctx;
      const [outBytes, inBytes, outSung, inSung] = await Promise.all([
        analyzer.audioBytes(cur.fileKey),
        analyzer.audioBytes(next.fileKey),
        this.host.sungSpans(cur.fileKey).catch(() => null),
        this.host.sungSpans(next.fileKey).catch(() => null),
      ]);
      const [outBuffer, inBuffer] = await Promise.all([ctx.decodeAudioData(outBytes.slice(0)), ctx.decodeAudioData(inBytes.slice(0))]);
      if (!stillWanted()) return;
      const pos = this.host.front().expectedTrackTime ?? 0;
      const plan = planLoopMix(outGrid, outBuffer, next, inBuffer, pos + LOOP_SETUP_SEC, { out: outSung, in: inSung });
      if (!plan) return;
      const [outDeck, inDeck] = this.loopDecks;
      await outDeck.load(cur.fileKey, outBuffer, Math.max(0, cur.durationSec - OUT_REGION_SEC), cur.durationSec);
      await inDeck.load(next.fileKey, inBuffer, 0, Math.min(next.durationSec, IN_REGION_SEC));
      if (!stillWanted()) return;
      this.loopPlan = plan;
    } catch (error) {
      console.warn('automix: loop mix not prepared', error);
    } finally {
      if (this.loopPreparing === key && !this.loopPlan) this.loopPreparing = null;
    }
  }

  private async scheduleLoop(cur: TrackAnalysis): Promise<void> {
    const plan = this.loopPlan!;
    const queued = this.host.next();
    if (!this.loopDecks || queued?.videoId !== plan.in.key || cur.fileKey !== plan.out.key) {
      this.loopPlan = null;
      return;
    }
    const from = this.host.front();
    const to = this.host.back();
    await from.anchorPrecise();
    const now = this.host.ctx.currentTime;
    const t0 = from.timeline.ctxTimeAt(plan.out.loop[0], now);
    if (t0 === null || t0 - now < 1) {
      this.loopPlan = null;
      return;
    }
    const [outDeck, inDeck] = this.loopDecks;
    const times = scheduleLoopMix(plan, outDeck, inDeck, t0);
    this.host.hold(true);

    const og = from.gain.gain;
    og.cancelScheduledValues(now);
    og.setValueAtTime(1, t0 - 0.025);
    og.linearRampToValueAtTime(0, t0);
    from.pauseAt(t0 + 0.06);
    const ig = to.gain.gain;
    ig.cancelScheduledValues(now);
    ig.setValueAtTime(0, now);
    ig.setValueAtTime(0, times.elementAt);
    ig.linearRampToValueAtTime(1, times.elementAt + 0.025);
    to.eq.reset();
    to.playAt({ ctxTime: times.elementAt - 1.5, trackTime: times.elementTrackTime - 1.5, rate: 1 });
    to.lock(null);

    this.loopTimes = times;
    this.plan = {
      t0,
      durationSec: times.elementAt - t0,
      mixInTrackTime: plan.in.loop[0],
      mixOutTrackTime: plan.out.loop[0],
      rate: plan.fold,
      fallback: false,
      style: 'loop',
      reason: null,
    };
    this.blend = { from, to, handedOff: false };
    this.window = { videoId: cur.fileKey, startSec: plan.out.loop[0], endSec: plan.out.loop[1] };
    this.beatSec = plan.out.beatSec;
    this.state = 'scheduled';
  }

  private completeTransition(): void {
    const blend = this.blend;
    if (blend && this.loopTimes && this.loopDecks) {
      for (const d of this.loopDecks) d.stop();
      this.loopTimes = null;
      this.host.hold(false);
    }
    this.loopPlan = null;
    this.loopPreparing = null;
    if (blend) {
      if (!blend.handedOff) this.host.handoff();
      blend.from.cancel();
      blend.to.unfollow();
      this.settling = blend.to;
      releaseWhenSettled(blend.to, () => this.settling === blend.to);
      this.host.finished();
    }
    if (this.currentKey) this.history.unshift(this.currentKey);
    if (this.nextKey) this.history.unshift(this.nextKey);
    this.history.length = Math.min(this.history.length, 50);
    this.currentKey = this.nextKey;
    this.nextKey = null;
    this.nextLoadedKey = null;
    this.plan = null;
    this.blend = null;
    this.window = null;
    this.candidates = [];

    this.state = 'playing';
  }

  private push(): void {
    this.onUpdate(this.snapshot);
  }
}

function tags(t: QueuedTrack) {
  return { title: t.title, artist: t.artist };
}

function stubAnalysis(t: QueuedTrack, durationSec: number): TrackAnalysis {
  return {
    fileKey: t.videoId,
    path: `youtube:${t.videoId}`,
    tags: tags(t),
    durationSec,
    bpm: null,
    beatIntervalSec: null,
    firstDownbeatSec: null,
    gridConfidence: 0,
    camelot: null,
    keyName: null,
    keyConfidence: 0,
    energy: 0.5,
    energyCurve: [],
    introEndSec: null,
    outroStartSec: null,
    analysisVersion: 0,
  };
}

function releaseWhenSettled(deck: Deck, isCurrent: () => boolean) {
  const check = () => {
    if (!isCurrent()) return;
    const now = deck.ctx.currentTime;
    if (Math.abs(deck.timeline.rateAt(now + 60) - deck.timeline.rateAt(now)) < 1e-6) deck.release();
    else setTimeout(check, 1000);
  };
  setTimeout(check, 1000);
}
