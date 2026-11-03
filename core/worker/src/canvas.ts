// Community canvases: the canvas.nuvx.lol page posts a song link, a passcode and an MP4; the worker keeps it in Workers KV
// under <videoId>.mp4 and serves it to the app at /canvas/<videoId>.mp4.

export const MAX_CANVAS_BYTES = 10 * 1024 * 1024;
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/** The 11-character video id from a YouTube Music / YouTube link (watch?v=, youtu.be/…) or a bare id; null otherwise. */
export function videoIdFromLink(link: string): string | null {
  const text = link.trim();
  if (VIDEO_ID.test(text)) return text;
  let url: URL;
  try { url = new URL(text); } catch { return null; }
  const host = url.hostname.replace(/^www\./, "");
  const id = host === "youtu.be" ? url.pathname.slice(1)
    : host === "music.youtube.com" || host === "youtube.com" || host === "m.youtube.com" ? url.searchParams.get("v")
    : null;
  return id && VIDEO_ID.test(id) ? id : null;
}

/** MP4 files carry an "ftyp" box at byte 4; checked instead of trusting the browser's file type. */
export function isMp4(bytes: Uint8Array): boolean {
  return bytes.length > 12 && String.fromCharCode(...bytes.subarray(4, 8)) === "ftyp";
}

/** Compares in constant time so the passcode can't be guessed byte by byte from response timing. */
export function samePasscode(given: string, expected: string): boolean {
  const a = new TextEncoder().encode(given), b = new TextEncoder().encode(expected);
  let diff = a.length ^ b.length;
  for (let i = 0; i < b.length; i++) diff |= (a[i] ?? 0) ^ b[i];
  return b.length > 0 && diff === 0;
}

/** A single "bytes=a-b" range within [size] as [start, endInclusive]; null for none, multi-range or out of bounds. */
export function parseRange(header: string | null, size: number): [number, number] | null {
  const m = header?.match(/^bytes=(\d*)-(\d*)$/);
  if (!m || (m[1] === "" && m[2] === "")) return null;
  const [start, end] = m[1] === "" ? [Math.max(0, size - Number(m[2])), size - 1] : [Number(m[1]), m[2] === "" ? size - 1 : Math.min(Number(m[2]), size - 1)];
  return start <= end && start < size ? [start, end] : null;
}
