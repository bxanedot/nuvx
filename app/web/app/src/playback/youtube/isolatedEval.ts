type Reply = { id: number; result?: unknown; error?: string };
type Port = Pick<Worker, 'postMessage' | 'terminate'> & {
  onmessage: ((event: MessageEvent<Reply>) => void) | null;
  onerror: ((event: ErrorEvent) => void) | null;
};

const TIMEOUT_MS = 10_000;

/**
 * Evaluates code from YouTube inside a worker, so it cannot reach the page, its storage or the
 * extension bridge. A run that throws, crashes the worker or exceeds the timeout rejects, and a
 * crash or timeout replaces the worker.
 */
export function createIsolatedEval(spawn: () => Port, timeoutMs = TIMEOUT_MS) {
  let port: Port | undefined;
  let nextId = 0;
  const pending = new Map<number, { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }>();

  const reset = (why: string) => {
    port?.terminate();
    port = undefined;
    for (const [id, waiter] of pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(why));
      pending.delete(id);
    }
  };

  const open = (): Port => {
    if (port) return port;
    const created = spawn();
    created.onmessage = ({ data }) => {
      const waiter = pending.get(data.id);
      if (!waiter) return;
      pending.delete(data.id);
      clearTimeout(waiter.timer);
      if (data.error !== undefined) waiter.reject(new Error(data.error));
      else waiter.resolve(data.result);
    };
    created.onerror = (event) => reset(`decipher worker failed: ${event.message}`);
    port = created;
    return created;
  };

  return (code: string): Promise<unknown> =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      const timer = setTimeout(() => reset('decipher timed out'), timeoutMs);
      pending.set(id, { resolve, reject, timer });
      open().postMessage({ id, code });
    });
}
