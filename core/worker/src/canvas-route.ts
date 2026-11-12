import { MAX_CANVAS_BYTES, commitCanvas, isMp4, samePasscode, shareCanvas, videoIdFromLink } from "./canvas.ts";
import { readRequestBytes } from "./request-body.ts";

export interface CanvasUploadEnv {
  CANVAS_PASSCODE?: string;
  CANVAS_GITHUB_TOKEN?: string;
  CANVAS_LIMIT: { limit(input: { key: string }): Promise<{ success: boolean }> };
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "access-control-allow-origin": "*" } });

export async function handleCanvasUpload(req: Request, env: CanvasUploadEnv, fetcher: typeof fetch = fetch): Promise<Response> {
  if (!env.CANVAS_PASSCODE || !env.CANVAS_GITHUB_TOKEN) return json({ error: "Uploads are not set up yet." }, 503);
  if (!(await env.CANVAS_LIMIT.limit({ key: req.headers.get("cf-connecting-ip") ?? "local" })).success) return json({ error: "Too many tries. Wait a minute." }, 429);
  const body = await readRequestBytes(req, MAX_CANVAS_BYTES + 64 * 1024);
  if (!body) return json({ error: "The video is over 10 MB." }, 413);
  let form: FormData;
  try {
    const contentType = req.headers.get("content-type");
    form = await new Response(body, { headers: contentType ? { "content-type": contentType } : undefined }).formData();
  } catch { return json({ error: "Send the form from canvas.nuvx.lol." }, 400); }
  if (!samePasscode(String(form.get("passcode") ?? ""), env.CANVAS_PASSCODE)) return json({ error: "Wrong passcode." }, 403);
  const id = videoIdFromLink(String(form.get("link") ?? ""));
  if (!id) return json({ error: "That isn't a YouTube Music song link." }, 400);
  const video = form.get("video");
  if (!(video instanceof File)) return json({ error: "Choose a video." }, 400);
  if (video.size > MAX_CANVAS_BYTES) return json({ error: "The video is over 10 MB." }, 413);
  const bytes = new Uint8Array(await video.arrayBuffer());
  if (!isMp4(bytes)) return json({ error: "The video must be an MP4." }, 415);
  const { status, message } = await commitCanvas(id, bytes, env.CANVAS_GITHUB_TOKEN, fetcher);
  if (status !== 200 && status !== 201) return json({ error: `GitHub refused the upload (${status}${message ? `: ${message}` : ""}).` }, 502);
  const shared = await shareCanvas(id, env.CANVAS_GITHUB_TOKEN, fetcher);
  return json({ ok: true, id, replaced: status === 200, shared });
}
