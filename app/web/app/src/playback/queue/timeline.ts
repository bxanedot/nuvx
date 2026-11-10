import type { Track } from '@/data/music/types';

export type QueueTier = 'context' | 'user' | 'autoplay';

export type QueueSourceType = 'album' | 'playlist' | 'artist' | 'library' | 'search' | 'home' | 'radio' | 'queue' | 'other';

export interface QueueSource {
  title: string;
  type: QueueSourceType;
  id?: string | null;
}

export interface QueueEntry {
  entryId: string;
  track: Track;
  tier: QueueTier;
  source: QueueSource | null;
}

export const BACK_RESTARTS_AFTER_SEC = 10;

let counter = 0;
const newEntryId = () => `${Date.now().toString(36)}-${(counter++).toString(36)}-${Math.random().toString(36).slice(2, 6)}`;

export function asEntry(track: Track, tier: QueueTier, source: QueueSource | null, entryId?: string): QueueEntry {
  return { entryId: entryId ?? newEntryId(), track, tier, source };
}

function upcomingUserQueue(timeline: readonly QueueEntry[], currentIndex: number): QueueEntry[] {
  if (currentIndex < 0 || currentIndex >= timeline.length) return [];
  return timeline.slice(currentIndex + 1).filter((e) => e.tier === 'user');
}

export function buildContextQueue(
  current: readonly QueueEntry[],
  currentIndex: number,
  tracks: readonly Track[],
  selectedIndex: number,
  source: QueueSource,
): { timeline: QueueEntry[]; startIndex: number } {
  if (!tracks.length) return { timeline: [], startIndex: 0 };
  const kept = upcomingUserQueue(current, currentIndex);
  const entries = tracks.map((t) => asEntry(t, 'context', source));
  const at = Math.min(Math.max(selectedIndex, 0), entries.length - 1);
  return {
    timeline: [...entries.slice(0, at), entries[at]!, ...kept, ...entries.slice(at + 1)],
    startIndex: at,
  };
}

export function buildOneOffQueue(
  current: readonly QueueEntry[],
  currentIndex: number,
  track: Track,
  source: QueueSource,
): QueueEntry[] {
  return [asEntry(track, 'context', source), ...upcomingUserQueue(current, currentIndex)];
}

export function userQueueInsertionIndex(timeline: readonly QueueEntry[], currentIndex: number, isNext: boolean): number {
  if (!timeline.length) return 0;
  const start = Math.min(Math.max(currentIndex + 1, 0), timeline.length);
  if (isNext) return start;
  for (let i = start; i < timeline.length; i++) if (timeline[i]!.tier !== 'user') return i;
  return timeline.length;
}

export interface JumpPlan {
  isPureSeek: boolean;
  order: number[];
  newIndex: number;
  promoteTarget: boolean;
}

const range = (from: number, to: number) => Array.from({ length: Math.max(0, to - from) }, (_, i) => from + i);

export function computeJumpOrder(tiers: readonly QueueTier[], currentIndex: number, targetIndex: number): JumpPlan | null {
  const n = tiers.length;
  if (currentIndex < 0 || currentIndex >= n || targetIndex < 0 || targetIndex >= n) return null;
  const identity = range(0, n);
  if (targetIndex <= currentIndex) return { isPureSeek: true, order: identity, newIndex: targetIndex, promoteTarget: false };
  const where = (from: number, tier: QueueTier) => range(from, n).filter((i) => tiers[i] === tier);
  const between = (tier: QueueTier) => range(currentIndex + 1, targetIndex).filter((i) => tiers[i] === tier);
  const history = range(0, currentIndex + 1);

  switch (tiers[targetIndex]) {
    case 'context': {
      const crossedUser = between('user');
      if (!crossedUser.length) return { isPureSeek: true, order: identity, newIndex: targetIndex, promoteTarget: false };
      const bypassedContext = between('context');
      const after = targetIndex + 1;
      const order = [
        ...history,
        ...bypassedContext,
        targetIndex,
        ...crossedUser,
        ...where(after, 'user'),
        ...where(after, 'context'),
        ...where(after, 'autoplay'),
      ];
      return { isPureSeek: false, order, newIndex: history.length + bypassedContext.length, promoteTarget: false };
    }
    case 'user': {
      const order = [
        ...history,
        targetIndex,
        ...where(targetIndex + 1, 'user'),
        ...where(currentIndex + 1, 'context'),
        ...where(currentIndex + 1, 'autoplay'),
      ];
      return { isPureSeek: false, order, newIndex: history.length, promoteTarget: false };
    }
    case 'autoplay': {
      const order = [...history, targetIndex, ...where(currentIndex + 1, 'user'), ...where(targetIndex + 1, 'autoplay')];
      return { isPureSeek: false, order, newIndex: history.length, promoteTarget: true };
    }
    default:
      return null;
  }
}

export function applyJump(timeline: readonly QueueEntry[], currentIndex: number, targetIndex: number): { timeline: QueueEntry[]; index: number } | null {
  const plan = computeJumpOrder(
    timeline.map((e) => e.tier),
    currentIndex,
    targetIndex,
  );
  if (!plan) return null;
  if (plan.isPureSeek) return { timeline: [...timeline], index: plan.newIndex };
  const target = timeline[targetIndex]!;
  return {
    timeline: plan.order.map((i) => {
      const entry = timeline[i]!;
      if (i !== targetIndex || !plan.promoteTarget) return entry;
      return {
        ...entry,
        tier: 'context' as const,
        source: { title: target.source?.title || target.track.title, type: target.source?.type ?? 'queue', id: target.source?.id ?? null },
      };
    }),
    index: plan.newIndex,
  };
}

export function upcomingUserQueueIndices(tiers: readonly QueueTier[], currentIndex: number): number[] {
  return range(Math.max(currentIndex + 1, 0), tiers.length).filter((i) => tiers[i] === 'user');
}

export function playedUserQueueIndices(tiers: readonly QueueTier[], currentIndex: number): number[] {
  if (currentIndex <= 0 || currentIndex >= tiers.length || tiers[currentIndex] !== 'context') return [];
  return range(0, currentIndex).filter((i) => tiers[i] === 'user');
}

export function shuffled<T>(items: readonly T[], random: () => number = Math.random): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  return out;
}

export function avoidIdentityShuffle(original: readonly number[], shuffledOrder: number[]): number[] {
  if (original.length <= 1 || shuffledOrder.some((v, i) => v !== original[i])) return shuffledOrder;
  return [...shuffledOrder.slice(1), shuffledOrder[0]!];
}

export function shuffledStartingOrder(entries: readonly QueueEntry[], startIndex: number, random?: () => number): QueueEntry[] {
  if (!entries.length) return [];
  const at = Math.min(Math.max(startIndex, 0), entries.length - 1);
  const rest = entries.filter((_, i) => i !== at);
  return [
    entries[at]!,
    ...rest.filter((e) => e.tier === 'user'),
    ...shuffled(rest.filter((e) => e.tier === 'context'), random),
    ...shuffled(rest.filter((e) => e.tier === 'autoplay'), random),
  ];
}

export function shuffledUpcomingOrder(upcoming: readonly QueueTier[], random?: () => number): number[] {
  const of = (tier: QueueTier) => range(0, upcoming.length).filter((i) => upcoming[i] === tier);
  const context = of('context');
  const autoplay = of('autoplay');
  return [
    ...of('user'),
    ...avoidIdentityShuffle(context, shuffled(context, random)),
    ...avoidIdentityShuffle(autoplay, shuffled(autoplay, random)),
  ];
}

export function sectionedOrder(restored: readonly number[], upcoming: readonly QueueTier[]): number[] {
  return (['user', 'context', 'autoplay'] as const).flatMap((tier) => restored.filter((i) => upcoming[i] === tier));
}

export function autoplaySectionStart(tiers: readonly QueueTier[], currentIndex: number): number {
  const after = Math.min(Math.max(currentIndex + 1, 0), tiers.length);
  for (let i = after; i < tiers.length; i++) if (tiers[i] === 'autoplay') return i;
  return tiers.length;
}
