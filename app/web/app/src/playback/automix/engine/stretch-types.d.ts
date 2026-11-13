
  export interface ScheduleOptions {
    output?: number;
    outputTime?: number;
    active?: boolean;
    input?: number;
    rate?: number;
    semitones?: number;
    tonalityHz?: number;
    formantSemitones?: number;
    formantCompensation?: boolean;
    formantBaseHz?: number;
    loopStart?: number;
    loopEnd?: number;
  }

  export interface SignalsmithStretchNode extends AudioWorkletNode {
    readonly inputTime: number;
    schedule(opts: ScheduleOptions, adjustPrevious?: boolean): void;
    start(when?: number, offset?: number, duration?: number): void;
    stop(when?: number): void;
    addBuffers(buffers: Float32Array[]): Promise<number>;
    dropBuffers(toSeconds?: number): Promise<{ start: number; end: number } | void>;
    latency(): Promise<number>;
    setUpdateInterval(seconds: number, callback?: (inputTime: number) => void): void;
    configure(opts: {
      blockMs?: number | null;
      intervalMs?: number;
      splitComputation?: boolean;
      preset?: 'default' | 'cheaper';
    }): void;
  }

  export default function SignalsmithStretch(
    ctx: BaseAudioContext,
    channelOptions?: Partial<AudioWorkletNodeOptions>,
  ): Promise<SignalsmithStretchNode>;
