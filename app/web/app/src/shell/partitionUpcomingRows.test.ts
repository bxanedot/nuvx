import { describe, expect, it } from 'vitest';
import { partitionUpcomingRows } from './partitionUpcomingRows';

describe('Up Next queue partition', () => {
  it('preserves order, original indices and the AutoPlay boundary', () => {
    const queue = ['already played', 'current', 'queued one', 'queued two', 'auto one', 'auto two'];
    const result = partitionUpcomingRows(queue, 1, 4);
    expect(result.beforeAutoplay).toEqual([
      { entry: 'queued one', at: 2 },
      { entry: 'queued two', at: 3 },
    ]);
    expect(result.afterAutoplay).toEqual([
      { entry: 'auto one', at: 4 },
      { entry: 'auto two', at: 5 },
    ]);
    expect(partitionUpcomingRows(queue, 5, 6)).toEqual({ beforeAutoplay: [], afterAutoplay: [] });
    expect(partitionUpcomingRows(queue, -1, 4).beforeAutoplay.map(({ at }) => at)).toEqual([0, 1, 2, 3]);
  });

  it('does not read or wrap played entries when the queue is large', () => {
    const queue = Array.from({ length: 10_000 }, (_, i) => i);
    for (let i = 0; i < 9_000; i++) {
      Object.defineProperty(queue, i, { get: () => { throw new Error('read a played entry'); } });
    }
    const result = partitionUpcomingRows(queue, 9_000, 9_500);
    expect(result.beforeAutoplay).toHaveLength(499);
    expect(result.afterAutoplay).toHaveLength(500);
    expect(result.beforeAutoplay[0]).toEqual({ entry: 9_001, at: 9_001 });
    expect(result.afterAutoplay.at(-1)).toEqual({ entry: 9_999, at: 9_999 });
  });
});
