/** Build only the queue rows that can be displayed, without allocating one object per played track. */
export interface IndexedEntry<T> {
  entry: T;
  at: number;
}

export function partitionUpcomingRows<T>(queue: readonly T[], currentIndex: number, autoplayAt: number) {
  const beforeAutoplay: IndexedEntry<T>[] = [];
  const afterAutoplay: IndexedEntry<T>[] = [];

  for (let at = Math.max(0, currentIndex + 1); at < queue.length; at++) {
    if (!(at in queue)) continue;
    const row = { entry: queue[at]!, at };
    (at < autoplayAt ? beforeAutoplay : afterAutoplay).push(row);
  }

  return { beforeAutoplay, afterAutoplay };
}
