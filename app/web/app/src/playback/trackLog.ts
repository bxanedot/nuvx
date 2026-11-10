import { create } from 'zustand';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface LogLine {
  id: number;
  t: number;
  level: LogLevel;
  about: string | null;
  message: string;
}

const MAX_LINES = 1500;

interface TrackLogState {
  lines: LogLine[];
  clear: () => void;
}

export const useTrackLog = create<TrackLogState>()((set) => ({
  lines: [],
  clear: () => set({ lines: [] }),
}));

let nextId = 0;

function push(level: LogLevel, message: string, about: string | null) {
  const line: LogLine = { id: nextId++, t: performance.now(), level, about, message };
  useTrackLog.setState((s) => ({
    lines: s.lines.length >= MAX_LINES ? [...s.lines.slice(-MAX_LINES + 1), line] : [...s.lines, line],
  }));
  const tag = about ? `[${about}] ` : '';
  const out = level === 'error' ? console.error : level === 'warn' ? console.warn : console.debug;
  out(`nuvx ${tag}${message}`);
}

export const trackLog = {
  d: (message: string, about: string | null = null) => push('debug', message, about),
  i: (message: string, about: string | null = null) => push('info', message, about),
  w: (message: string, about: string | null = null) => push('warn', message, about),
  e: (message: string, about: string | null = null) => push('error', message, about),
};

export async function timed<T>(label: string, about: string | null, block: () => Promise<T>): Promise<T> {
  const start = performance.now();
  try {
    return await block();
  } finally {
    trackLog.d(`TIMING ${label}: ${Math.round(performance.now() - start)}ms`, about);
  }
}
