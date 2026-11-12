export const MAX_CANVAS_BYTES = 10 * 1024 * 1024;
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const REPO_FILE = "https://api.github.com/repos/bxanedot/nuvx/contents/core/canvas/";

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

export function isMp4(bytes: Uint8Array): boolean {
  const boxes = (start: number, end: number): { type: string; start: number; payload: number; end: number }[] | null => {
    const result = [];
    for (let offset = start; offset < end;) {
      if (end - offset < 8) return null;
      const view = new DataView(bytes.buffer, bytes.byteOffset + offset, end - offset);
      let size = view.getUint32(0), header = 8;
      if (size === 1) {
        if (end - offset < 16) return null;
        const extended = view.getBigUint64(8);
        if (extended > BigInt(Number.MAX_SAFE_INTEGER)) return null;
        size = Number(extended); header = 16;
      } else if (size === 0) size = end - offset;
      const type = String.fromCharCode(...bytes.subarray(offset + 4, offset + 8));
      if (type === "uuid") header += 16;
      if (size < header || size > end - offset) return null;
      result.push({ type, start: offset, payload: offset + header, end: offset + size });
      offset += size;
    }
    return result;
  };

  const top = boxes(0, bytes.length);
  if (!top || top[0]?.type !== "ftyp") return false;
  const ftyp = top[0];
  if (ftyp.end - ftyp.start < 16 || (ftyp.end - ftyp.start - 16) % 4 !== 0) return false;
  if (!top.some(box => box.type === "mdat" && box.end > box.payload)) return false;
  for (const moov of top) {
    if (moov.type !== "moov") continue;
    for (const trak of boxes(moov.payload, moov.end) ?? []) {
      if (trak.type !== "trak") continue;
      for (const mdia of boxes(trak.payload, trak.end) ?? []) {
        if (mdia.type !== "mdia") continue;
        for (const hdlr of boxes(mdia.payload, mdia.end) ?? []) {
          if (hdlr.type === "hdlr" && hdlr.end - hdlr.start >= 20 && String.fromCharCode(...bytes.subarray(hdlr.start + 16, hdlr.start + 20)) === "vide") return true;
        }
      }
    }
  }
  return false;
}

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

export function canvasKey(title: string, artist: string): string {
  const clean = (value: string) => value.trim().replace(/\s+/g, " ").toLowerCase();
  return `${clean(title)}|${clean(artist).replace(/ - topic$/i, "")}`;
}

export async function shareCanvas(id: string, token: string, fetchFn: typeof fetch = fetch): Promise<boolean> {
  try {
    const embed = await fetchFn(`https://www.youtube.com/oembed?url=${encodeURIComponent(`https://www.youtube.com/watch?v=${id}`)}&format=json`);
    if (!embed.ok) return false;
    const { title, author_name } = await embed.json() as { title?: string; author_name?: string };
    if (!title || !author_name) return false;
    const headers = { authorization: `Bearer ${token}`, accept: "application/vnd.github+json", "user-agent": "nuvx-canvas-upload" };
    const path = `${REPO_FILE}index.json`;
    for (let attempt = 0; attempt < 3; attempt++) {
      const existing = await fetchFn(path, { headers });
      let sha: string | undefined;
      let index: Record<string, string> = {};
      if (existing.ok) {
        const file = await existing.json() as { sha?: string; content?: string };
        sha = file.sha;
        const bytes = Uint8Array.from(atob((file.content ?? "").replace(/\s/g, "")), char => char.charCodeAt(0));
        index = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, string>;
      } else if (existing.status !== 404) return false;
      const key = canvasKey(title, author_name);
      if (index[key] === id) return true;
      index[key] = id;
      const content = base64(new TextEncoder().encode(JSON.stringify(index, null, 2) + "\n"));
      const put = await fetchFn(path, { method: "PUT", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify({ message: `Share canvas ${id}`, content, branch: "main", ...(sha ? { sha } : {}) }) });
      if (put.ok) return true;
      if (put.status !== 409 || attempt === 2) return false;
    }
    return false;
  } catch { return false; }
}

export async function canvasExists(id: string, token: string, fetchFn: typeof fetch = fetch): Promise<boolean> {
  try {
    const response = await fetchFn(`${REPO_FILE}${id}.mp4`, { headers: { authorization: `Bearer ${token}`, accept: "application/vnd.github+json", "user-agent": "nuvx-canvas-upload" } });
    return response.ok;
  } catch { return false; }
}
