// SPDX-License-Identifier: AGPL-3.0-only
import SignalsmithStretch, { type SignalsmithStretchNode } from 'signalsmith-stretch';
import { ThreeBandEQ } from './eq';

export interface LoopChange {
  output: number;
  input: number;
  rate: number;
  active: boolean;
  loop: [number, number] | null;
}

const POST_AHEAD_SEC = 0.25;
const DISPATCH_MS = 20;

export class LoopDeck {
  readonly eq: ThreeBandEQ;
  readonly gain: GainNode;
  readonly sweep: { highpass: BiquadFilterNode; lowpass: BiquadFilterNode };
  regionStart = 0;
  regionEnd = 0;
  loadedKey: string | null = null;
  latencySec = 0.12;
  private pending: LoopChange[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;

  private constructor(
    readonly ctx: AudioContext,
    readonly stretch: SignalsmithStretchNode,
    destination: AudioNode,
  ) {
    this.eq = new ThreeBandEQ(ctx);
    this.sweep = {
      highpass: new BiquadFilterNode(ctx, { type: 'highpass', frequency: 20, Q: 0.7 }),
      lowpass: new BiquadFilterNode(ctx, { type: 'lowpass', frequency: 20000, Q: 0.7 }),
    };
    this.gain = new GainNode(ctx, { gain: 0 });
    stretch.connect(this.eq.input);
    stretch.setUpdateInterval(0.05);
    this.eq.output.connect(this.sweep.highpass).connect(this.sweep.lowpass).connect(this.gain).connect(destination);
  }

  static async create(ctx: AudioContext, destination: AudioNode): Promise<LoopDeck> {
    const stretch = await SignalsmithStretch(ctx);
    const deck = new LoopDeck(ctx, stretch, destination);
    deck.latencySec = await stretch.latency();
    return deck;
  }

  async load(key: string, buffer: AudioBuffer, startSec: number, endSec: number): Promise<void> {
    this.stop();
    await this.stretch.dropBuffers();
    const from = Math.max(0, Math.floor(startSec * buffer.sampleRate));
    const to = Math.min(buffer.length, Math.ceil(endSec * buffer.sampleRate));
    const channels: Float32Array[] = [];
    for (let c = 0; c < Math.min(2, buffer.numberOfChannels); c++) channels.push(buffer.getChannelData(c).slice(from, to));
    if (channels.length === 1) channels.push(channels[0]!.slice());
    await this.stretch.addBuffers(channels);
    this.regionStart = from / buffer.sampleRate;
    this.regionEnd = to / buffer.sampleRate;
    this.loadedKey = key;
  }

  schedule(changes: LoopChange[]): void {
    this.pending.push(...changes);
    this.pending.sort((a, b) => a.output - b.output);
    this.timer ??= setInterval(() => this.dispatch(), DISPATCH_MS);
    this.dispatch();
  }

  private dispatch() {
    const now = this.ctx.currentTime;
    while (this.pending.length && this.pending[0]!.output - now < POST_AHEAD_SEC) {
      const c = this.pending.shift()!;
      const r = this.regionStart;
      this.stretch.schedule({
        output: c.output,
        input: c.input - r,
        rate: c.rate,
        active: c.active,
        loopStart: c.loop ? c.loop[0] - r : 0,
        loopEnd: c.loop ? c.loop[1] - r : 0,
      });
    }
    if (!this.pending.length && this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  stop(): void {
    this.pending = [];
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    this.stretch.schedule({ active: false });
    const now = this.ctx.currentTime;
    this.gain.gain.cancelScheduledValues(now);
    this.gain.gain.setValueAtTime(0, now);
    this.eq.reset();
    for (const [param, open] of [
      [this.sweep.highpass.frequency, 20],
      [this.sweep.lowpass.frequency, 20000],
    ] as const) {
      param.cancelScheduledValues(now);
      param.setValueAtTime(open, now);
    }
  }

  get inputTime(): number {
    return this.stretch.inputTime + this.regionStart;
  }
}
