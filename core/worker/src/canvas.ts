// Community canvas uploads: the canvas.nuvx.lol page posts a song link, a passcode and an MP4; the worker commits it to
// github.com/bxanedot/nuvx as canvas/<videoId>.mp4, which the canvas-pages workflow publishes to canvas.nuvx.lol.

export const MAX_CANVAS_BYTES = 10 * 1024 * 1024;
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const REPO_FILE = "https://api.github.com/repos/bxanedot/nuvx/contents/canvas/";

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

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

/** Creates or replaces canvas/<id>.mp4 in the repo. Returns GitHub's status (200/201 on success) and its message on failure. */
export async function commitCanvas(id: string, bytes: Uint8Array, token: string, fetchFn: typeof fetch = fetch): Promise<{ status: number; message?: string }> {
  const headers = { authorization: `Bearer ${token}`, accept: "application/vnd.github+json", "user-agent": "nuvx-canvas-upload" };
  const existing = await fetchFn(`${REPO_FILE}${id}.mp4`, { headers });
  const sha = existing.ok ? ((await existing.json()) as { sha?: string }).sha : undefined;
  const put = await fetchFn(`${REPO_FILE}${id}.mp4`, {
    method: "PUT",
    headers: { ...headers, "content-type": "application/json" },
    body: JSON.stringify({ message: `${sha ? "Replace" : "Add"} canvas ${id}`, content: base64(bytes), branch: "main", ...(sha ? { sha } : {}) }),
  });
  if (put.ok) return { status: put.status };
  return { status: put.status, message: ((await put.json().catch(() => ({}))) as { message?: string }).message };
}
