import { AutoDJ, type AutomixHost, type AutoDJSnapshot } from './engine/autodj';
import { Deck } from './engine/deck';
import { LoopDeck } from './engine/loopDeck';
import type { TransitionPlan } from './engine/transition-scheduler';

export type { AutomixHost, AutoDJSnapshot, TransitionPlan };
export type { MixWindow } from './engine/autodj';
export type { LoopMixPlan, SungSpans } from './engine/loopMix';
export { sungSpansFromLines } from './engine/loopMix';
export { analyzer } from './analysis/analyzer';

export { AUTOMIX_BARS } from './choices';

export interface AutomixGraph {
  ctx: AudioContext;
  master: GainNode;
  decks: Deck[];
  dj: AutoDJ;
}

export function createAutomixGraph(
  audios: HTMLAudioElement[],
  host: (decks: Deck[]) => Omit<AutomixHost, 'ctx' | 'loopDecks'>,
  onUpdate?: (snap: AutoDJSnapshot) => void,
): AutomixGraph {
  const ctx = new AudioContext({ latencyHint: 'playback' });
  void ctx.resume();
  const master = new GainNode(ctx, { gain: 1 });
  master.connect(ctx.destination);
  const decks = audios.map((audio, n) => new Deck(n === 0 ? 'A' : 'B', ctx, audio, master));
  let loops: Promise<[LoopDeck, LoopDeck]> | null = null;
  const loopDecks = () => (loops ??= Promise.all([LoopDeck.create(ctx, master), LoopDeck.create(ctx, master)]));
  const dj = new AutoDJ({ ctx, loopDecks, ...host(decks) }, onUpdate);
  return { ctx, master, decks, dj };
}
