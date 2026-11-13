// SPDX-License-Identifier: AGPL-3.0-only
import { ThreeBandEQ } from './eq';
import { Timeline } from './timeline';

export interface LoadedTrackMeta {
  fileKey: string;
  title: string;
  artist: string;
}

export interface PlayOptions {
  ctxTime?: number;
  trackTime: number;
  rate?: number;
}

type Change = { output: number; input: number; rate: number; active: boolean };

const SEEK_ERROR_SEC = 0.25;
const DEADBAND_SEC = 0.006;
const MAX_NUDGE = 0.03;
const MAX_QUIET_NUDGE = 0.1;
const SERVO_GAIN = 0.8;
const SERVO_MS = 40;

export class Deck {
  readonly eq: ThreeBandEQ;
  readonly gain: GainNode;
  readonly fader: GainNode;
  readonly sweep: { highpass: BiquadFilterNode; lowpass: BiquadFilterNode };
  readonly timeline = new Timeline();
  loaded: LoadedTrackMeta | null = null;
  latencySec = 0.12;

  private pending: Change[] = [];
  private dispatchTimer: ReturnType<typeof setInterval> | null = null;
  private locked = false;
  private leader: Deck | null = null;
  private errors: number[] = [];
  private seekingUntil = 0;

  constructor(
    readonly id: string,
    readonly ctx: AudioContext,
    readonly audio: HTMLAudioElement,
    destination: AudioNode,
  ) {
    this.eq = new ThreeBandEQ(ctx);
    this.gain = new GainNode(ctx, { gain: 1 });
    this.fader = new GainNode(ctx, { gain: 1 });
    this.sweep = {
      highpass: new BiquadFilterNode(ctx, { type: 'highpass', frequency: 20, Q: 0.7 }),
      lowpass: new BiquadFilterNode(ctx, { type: 'lowpass', frequency: 20000, Q: 0.7 }),
    };
    ctx.createMediaElementSource(audio).connect(this.eq.input);
    this.eq.output.connect(this.sweep.highpass).connect(this.sweep.lowpass).connect(this.gain).connect(this.fader).connect(destination);
    audio.preservesPitch = true;
  }

  get durationSec(): number {
    return Number.isFinite(this.audio.duration) ? this.audio.duration : 0;
  }

  get scheduleAhead(): number {
    return this.latencySec + 0.05;
  }

  anchor(): void {
    if (this.locked || this.pending.length) return;
    const now = this.ctx.currentTime;
    this.timeline.clear();
    this.timeline.add({
      output: now,
      input: this.audio.currentTime,
      rate: this.audio.playbackRate || 1,
      active: !this.audio.paused && !this.audio.ended,
    });
  }

  async anchorPrecise(samples = 9, spacingMs = 35): Promise<void> {
    if (this.audio.paused) return this.anchor();
    const offsets: number[] = [];
    const rate = this.audio.playbackRate || 1;
    for (let i = 0; i < samples; i++) {
      offsets.push(this.audio.currentTime - this.ctx.currentTime * rate);
      await new Promise((r) => setTimeout(r, spacingMs));
    }
    offsets.sort((a, b) => a - b);
    const offset = offsets[offsets.length >> 1]!;
    const now = this.ctx.currentTime;
    this.timeline.clear();
    this.timeline.add({ output: now, input: offset + now * rate, rate, active: !this.audio.paused });
  }

  playAt({ ctxTime, trackTime, rate = 1 }: PlayOptions): void {
    const output = ctxTime ?? this.ctx.currentTime + this.scheduleAhead;
    this.scheduleChange({ output, input: trackTime, rate, active: true });
  }

  setRate(rate: number, atCtxTime?: number): void {
    const output = atCtxTime ?? this.ctx.currentTime + this.scheduleAhead;
    const input = this.timeline.trackTimeAt(output);
    if (input === null) return;
    const active = this.timeline.activeAt(output);
    this.scheduleChange({ output, input, rate, active });
  }

  pause(): void {
    this.pauseAt(this.ctx.currentTime);
  }

  pauseAt(ctxTime: number): void {
    const input = this.timeline.trackTimeAt(ctxTime) ?? this.audio.currentTime;
    const rate = this.timeline.rateAt(ctxTime) || 1;
    this.scheduleChange({ output: ctxTime, input, rate, active: false });
  }

  lock(leader: Deck | null = null): void {
    this.leader = leader;
    this.locked = true;
    this.errors = [];
    this.ensureTimer();
  }

  release(): void {
    this.locked = false;
    this.leader = null;
    const rate = this.timeline.rateAt(this.ctx.currentTime) || 1;
    if (this.pending.length === 0) this.audio.playbackRate = rate;
  }

  cancel(): void {
    this.pending = [];
    this.locked = false;
    this.leader = null;
    this.timeline.clear();
    const now = this.ctx.currentTime;
    this.gain.gain.cancelScheduledValues(now);
    this.gain.gain.setValueAtTime(1, now);
    this.eq.reset();
    this.resetSweep();
    this.audio.playbackRate = 1;
  }

  resetSweep(): void {
    const now = this.ctx.currentTime;
    for (const [param, open] of [
      [this.sweep.highpass.frequency, 20],
      [this.sweep.lowpass.frequency, 20000],
    ] as const) {
      param.cancelScheduledValues(now);
      param.setValueAtTime(open, now);
    }
  }

  private scheduleChange(p: Change): void {
    this.timeline.add(p);
    this.pending = this.pending.filter((q) => q.output < p.output);
    this.pending.push(p);
    const first = this.pending[0]!;
    if (first === p && p.active && this.audio.paused && p.output - this.ctx.currentTime > 0.5) {
      this.audio.currentTime = p.input;
    }
    this.ensureTimer();
    this.dispatch();
  }

  private ensureTimer() {
    this.dispatchTimer ??= setInterval(() => this.dispatch(), SERVO_MS);
  }

  private dispatch(): void {
    const now = this.ctx.currentTime;
    while (this.pending.length > 0) {
      const next = this.pending[0]!;
      const lead = next.active && this.audio.paused ? this.latencySec : 0;
      if (next.output - lead > now) break;
      this.pending.shift();
      this.apply(next, now);
    }
    if (this.locked) this.servo(now);
    if (this.pending.length === 0 && !this.locked && this.dispatchTimer !== null) {
      clearInterval(this.dispatchTimer);
      this.dispatchTimer = null;
    }
  }

  private apply(change: Change, now: number) {
    const audio = this.audio;
    if (!change.active) {
      if (!audio.paused) audio.pause();
      return;
    }
    audio.playbackRate = change.rate;
    if (audio.paused) {
      const target = Math.max(0, change.input + Math.max(0, now + this.latencySec - change.output) * change.rate);
      if (Math.abs(audio.currentTime - target) > 0.02) audio.currentTime = target;
      const asked = this.ctx.currentTime;
      const started = () => {
        const took = this.ctx.currentTime - asked;
        if (took > 0 && took < 1) this.latencySec = this.latencySec * 0.5 + took * 0.5;
      };
      audio.addEventListener('playing', started, { once: true });
      void audio.play().catch(() => {});
    }
  }

  unfollow(): void {
    this.leader = null;
  }

  private actualCtxTime(now: number): number | null {
    if (this.audio.paused || this.audio.seeking) return null;
    return this.timeline.ctxTimeAt(this.audio.currentTime, now - 10);
  }

  private servo(now: number) {
    const audio = this.audio;
    const at = this.leader?.actualCtxTime(now) ?? now;
    const expected = this.timeline.trackTimeAt(at);
    const rate = this.timeline.rateAt(at);
    if (expected === null || !this.timeline.activeAt(at) || audio.paused || audio.seeking || now < this.seekingUntil) return;
    const error = audio.currentTime - expected;
    if (Math.abs(error) > SEEK_ERROR_SEC) {
      audio.currentTime = expected + this.latencySec * rate;
      this.seekingUntil = now + 0.3;
      this.errors = [];
      return;
    }
    this.errors.push(error);
    if (this.errors.length > 5) this.errors.shift();
    const smoothed = [...this.errors].sort((a, b) => a - b)[this.errors.length >> 1]!;
    const limit = this.gain.gain.value < 0.3 ? MAX_QUIET_NUDGE : MAX_NUDGE;
    const nudge = Math.abs(smoothed) < DEADBAND_SEC ? 0 : Math.max(-limit, Math.min(limit, -smoothed * SERVO_GAIN * (limit / MAX_NUDGE)));
    const want = rate * (1 + nudge);
    if (Math.abs(audio.playbackRate - want) > 0.0015) audio.playbackRate = want;
  }

  get isPlaying(): boolean {
    return !this.audio.paused;
  }

  get expectedTrackTime(): number | null {
    return this.timeline.trackTimeAt(this.ctx.currentTime);
  }

  get reportedTrackTime(): number {
    return this.audio.currentTime;
  }

  setVolume(v: number): void {
    try {
      this.gain.gain.setTargetAtTime(v, this.ctx.currentTime, 0.02);
    } catch {
    }
  }
}
