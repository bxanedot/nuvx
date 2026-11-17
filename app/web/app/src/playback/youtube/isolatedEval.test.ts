import { describe, expect, it, vi } from 'vitest';
import { createIsolatedEval } from './isolatedEval';

// A stand-in worker that evaluates in-process, so the queueing, timeout and crash logic can be tested.
function fakeWorker(behaviour: 'run' | 'hang' | 'crash' = 'run') {
  const port = {
    terminated: false,
    onmessage: null as ((e: MessageEvent) => void) | null,
    onerror: null as ((e: ErrorEvent) => void) | null,
    postMessage({ id, code }: { id: number; code: string }) {
      if (behaviour === 'hang') return;
      if (behaviour === 'crash') return queueMicrotask(() => port.onerror?.({ message: 'boom' } as ErrorEvent));
      let reply: object;
      try { reply = { id, result: new Function(code)() }; } catch (error) { reply = { id, error: String(error) }; }
      queueMicrotask(() => port.onmessage?.({ data: reply } as MessageEvent));
    },
    terminate() { port.terminated = true; },
  };
  return port;
}

describe('createIsolatedEval', () => {
  it('returns what the code returns', async () => {
    const run = createIsolatedEval(() => fakeWorker());
    await expect(run('return { n: "abc".split("").reverse().join("") }')).resolves.toEqual({ n: 'cba' });
  });

  it('rejects when the code throws', async () => {
    const run = createIsolatedEval(() => fakeWorker());
    await expect(run('throw new Error("bad sig")')).rejects.toThrow('bad sig');
  });

  it('gives up on a hung run and starts a fresh worker next time', async () => {
    vi.useFakeTimers();
    const spawned: ReturnType<typeof fakeWorker>[] = [];
    let mode: 'hang' | 'run' = 'hang';
    const run = createIsolatedEval(() => { const w = fakeWorker(mode); spawned.push(w); return w; }, 100);
    const hung = run('return 1');
    vi.advanceTimersByTime(100);
    await expect(hung).rejects.toThrow('timed out');
    expect(spawned[0].terminated).toBe(true);
    vi.useRealTimers();
    mode = 'run';
    await expect(run('return 2')).resolves.toBe(2);
    expect(spawned).toHaveLength(2);
  });

  it('rejects everything waiting when the worker crashes', async () => {
    const run = createIsolatedEval(() => fakeWorker('crash'));
    await expect(run('return 1')).rejects.toThrow('boom');
  });
});
