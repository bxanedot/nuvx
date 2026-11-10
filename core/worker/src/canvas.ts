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
  return bytes.length > 12 && String.fromCharCode(...bytes.subarray(4, 8)) === "ftyp";
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
    const existing = await fetchFn(path, { headers });
    let sha: string | undefined;
    let index: Record<string, string> = {};
    if (existing.ok) {
      const file = await existing.json() as { sha?: string; content?: string };
      sha = file.sha;
      const bytes = Uint8Array.from(atob((file.content ?? "").replace(/\s/g, "")), char => char.charCodeAt(0));
      index = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, string>;
    } else if (existing.status !== 404) return false;
    index[canvasKey(title, author_name)] = id;
    const content = base64(new TextEncoder().encode(JSON.stringify(index, null, 2) + "\n"));
    const put = await fetchFn(path, { method: "PUT", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify({ message: `Share canvas ${id}`, content, branch: "main", ...(sha ? { sha } : {}) }) });
    return put.ok;
  } catch { return false; }
}
