import { config } from '@/config';

const ID_KEY = 'nuvx:presence-id';
const DEFAULT_INTERVAL_SEC = 300;
const MIN_INTERVAL_SEC = 60;
const MAX_INTERVAL_SEC = 3600;
const CLOSE_DEBOUNCE_MS = 2_000;

function installId(): string {
  try {
    const held = localStorage.getItem(ID_KEY);
    if (held) return held;
    const fresh = crypto.randomUUID();
    localStorage.setItem(ID_KEY, fresh);
    return fresh;
  } catch {
    return crypto.randomUUID();
  }
}

export function startLivePresence(): () => void {
  const server = config.partyServerUrl.replace(/\/+$/, '');
  if (!server) return () => {};
  const endpoint = `${server}/api/presence`;
  const id = installId();
  let intervalSec = DEFAULT_INTERVAL_SEC;
  let announced = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let closeTimer: ReturnType<typeof setTimeout> | undefined;

  const send = async (open: boolean) => {
    announced = open;
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, platform: 'browser', open }),
        keepalive: true,
      });
      if (!response.ok) return;
      const reply = (await response.json()) as { intervalSec?: unknown };
      if (typeof reply.intervalSec === 'number') intervalSec = reply.intervalSec;
    } catch {
    }
  };

  const loop = async () => {
    await send(true);
    const base = Math.min(Math.max(intervalSec, MIN_INTERVAL_SEC), MAX_INTERVAL_SEC) * 1000;
    timer = setTimeout(loop, base + (Math.random() * 0.2 - 0.1) * base);
  };

  const setOpen = (open: boolean) => {
    clearTimeout(timer);
    clearTimeout(closeTimer);
    timer = closeTimer = undefined;
    if (open) void loop();
    else if (announced) closeTimer = setTimeout(() => void send(false), CLOSE_DEBOUNCE_MS);
  };

  const onPageHide = () => {
    clearTimeout(timer);
    clearTimeout(closeTimer);
    if (announced) void send(false);
  };
  const onPageShow = () => {
    if (!announced) setOpen(true);
  };

  setOpen(true);
  window.addEventListener('pagehide', onPageHide);
  window.addEventListener('pageshow', onPageShow);
  return () => {
    window.removeEventListener('pagehide', onPageHide);
    window.removeEventListener('pageshow', onPageShow);
    onPageHide();
  };
}
