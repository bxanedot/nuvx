import {
  BRIDGE_CHANNEL,
  BRIDGE_PROTOCOL,
  EVENTS_PORT,
  isBridgeEventMessage,
  isBridgeResponse,
  type BridgeError,
  type BridgeEvent,
  type BridgeMethod,
  type BridgeRequest,
  type BridgeResponse,
  type ParamsOf,
  type ResultOf,
} from './protocol';

export interface Bridge {
  readonly transport: 'external' | 'relay';
  call<M extends BridgeMethod>(method: M, params: ParamsOf<M>, options?: CallOptions): Promise<ResultOf<M>>;
  subscribe(listener: (event: BridgeEvent) => void): () => void;
}

export interface CallOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
}

export class BridgeCallError extends Error {
  readonly code: BridgeError['code'] | 'timeout' | 'unavailable';

  constructor(code: BridgeCallError['code'], message: string) {
    super(message);
    this.name = 'BridgeCallError';
    this.code = code;
  }
}

const DEFAULT_TIMEOUT_MS = 30_000;

let nextId = 0;
const newId = () => `${Date.now().toString(36)}-${(nextId++).toString(36)}`;

function envelope<M extends BridgeMethod>(method: M, params: ParamsOf<M>): BridgeRequest<M> {
  return { channel: BRIDGE_CHANNEL, protocol: BRIDGE_PROTOCOL, direction: 'to-extension', id: newId(), method, params };
}

function unwrap<M extends BridgeMethod>(response: BridgeResponse<M>): ResultOf<M> {
  if (response.ok) return response.result;
  throw new BridgeCallError(response.error.code, response.error.message);
}

function withDeadline<T>(
  options: CallOptions | undefined,
  start: (settle: (outcome: { value: T } | { error: unknown }) => void) => () => void,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let done = false;
    let cleanup = () => {};
    const finish = (outcome: { value: T } | { error: unknown }) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      options?.signal?.removeEventListener('abort', onAbort);
      cleanup();
      if ('value' in outcome) resolve(outcome.value);
      else reject(outcome.error);
    };
    const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    const timer = setTimeout(
      () => finish({ error: new BridgeCallError('timeout', `No answer from the extension within ${timeoutMs} ms`) }),
      timeoutMs,
    );
    const onAbort = () => finish({ error: options?.signal?.reason ?? new DOMException('Aborted', 'AbortError') });
    if (options?.signal?.aborted) return onAbort();
    options?.signal?.addEventListener('abort', onAbort, { once: true });
    cleanup = start(finish);
  });
}

interface ChromePortLike {
  onMessage: { addListener(listener: (message: unknown) => void): void };
  onDisconnect: { addListener(listener: () => void): void };
  disconnect(): void;
}

interface ChromeRuntimeLike {
  sendMessage(extensionId: string, message: unknown, callback: (response: unknown) => void): void;
  connect?(extensionId: string, info: { name: string }): ChromePortLike;
  readonly lastError?: { message?: string } | undefined;
}

const RECONNECT_MS = 2000;

function keepPortOpen(open: () => ChromePortLike | null, listener: (event: BridgeEvent) => void): () => void {
  let port: ChromePortLike | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;

  const connect = () => {
    if (stopped || port) return;
    if (typeof document !== 'undefined' && document.hidden) return;
    port = open();
    if (!port) return;
    port.onMessage.addListener((message) => {
      const event = message as BridgeEvent | null;
      if (event && typeof event === 'object' && typeof event.event === 'string') listener(event);
    });
    port.onDisconnect.addListener(() => {
      void (globalThis as { chrome?: { runtime?: { lastError?: unknown } } }).chrome?.runtime?.lastError;
      port = null;
      if (!stopped) timer = setTimeout(connect, RECONNECT_MS);
    });
  };
  const onVisible = () => {
    if (!document.hidden) connect();
  };

  connect();
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisible);
  return () => {
    stopped = true;
    clearTimeout(timer);
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible);
    port?.disconnect();
    port = null;
  };
}

function chromeRuntime(): ChromeRuntimeLike | null {
  const runtime = (globalThis as { chrome?: { runtime?: Partial<ChromeRuntimeLike> } }).chrome?.runtime;
  return typeof runtime?.sendMessage === 'function' ? (runtime as ChromeRuntimeLike) : null;
}

export function externalBridge(extensionId: string): Bridge | null {
  const runtime = chromeRuntime();
  if (!runtime || !extensionId) return null;
  return {
    transport: 'external',
    call(method, params, options) {
      const request = envelope(method, params);
      return withDeadline(options, (settle) => {
        runtime.sendMessage(extensionId, request, (response) => {
          const failure = runtime.lastError?.message;
          if (failure) return settle({ error: new BridgeCallError('unavailable', failure) });
          if (!isBridgeResponse(response) || response.id !== request.id) {
            return settle({ error: new BridgeCallError('protocol', 'Malformed response from the extension') });
          }
          try {
            settle({ value: unwrap(response as BridgeResponse<typeof method>) });
          } catch (error) {
            settle({ error });
          }
        });
        return () => {};
      });
    },
    subscribe(listener) {
      return keepPortOpen(() => {
        try {
          return runtime.connect?.(extensionId, { name: EVENTS_PORT }) ?? null;
        } catch {
          return null;
        }
      }, listener);
    },
  };
}

export function relayBridge(target: Window = window): Bridge {
  return {
    transport: 'relay',
    call(method, params, options) {
      const request = envelope(method, params);
      return withDeadline(options, (settle) => {
        const onMessage = (event: MessageEvent) => {
          if (event.source !== target || event.origin !== target.location.origin) return;
          if (!isBridgeResponse(event.data) || event.data.id !== request.id) return;
          try {
            settle({ value: unwrap(event.data as BridgeResponse<typeof method>) });
          } catch (error) {
            settle({ error });
          }
        };
        target.addEventListener('message', onMessage);
        target.postMessage(request, target.location.origin);
        return () => target.removeEventListener('message', onMessage);
      });
    },
    subscribe(listener) {
      const onMessage = (event: MessageEvent) => {
        if (event.source !== target || event.origin !== target.location.origin) return;
        if (isBridgeEventMessage(event.data)) listener(event.data.payload);
      };
      target.addEventListener('message', onMessage);
      return () => target.removeEventListener('message', onMessage);
    },
  };
}

export interface DetectedExtension {
  bridge: Bridge;
  info: ResultOf<'ping'>;
}

export interface DetectOptions {
  chromiumExtensionId?: string | undefined;
  timeoutMs?: number;
}

export async function detectExtension(options: DetectOptions = {}): Promise<DetectedExtension | null> {
  const timeoutMs = options.timeoutMs ?? 1500;
  const candidates: Bridge[] = [];
  const external = options.chromiumExtensionId ? externalBridge(options.chromiumExtensionId) : null;
  if (external) candidates.push(external);
  if (typeof window !== 'undefined') candidates.push(relayBridge(window));

  for (const bridge of candidates) {
    try {
      const info = await bridge.call('ping', {}, { timeoutMs });
      return { bridge, info };
    } catch {
    }
  }
  return null;
}
