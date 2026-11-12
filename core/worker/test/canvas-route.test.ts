import { test } from "node:test";
import assert from "node:assert/strict";
import { handleCanvasUpload } from "../src/canvas-route.ts";
import { MAX_CANVAS_BYTES } from "../src/canvas.ts";

const ID = "9qnqYL0eNNI";
const box = (type: string, payload = new Uint8Array()) => {
  const bytes = new Uint8Array(payload.length + 8);
  new DataView(bytes.buffer).setUint32(0, bytes.length);
  bytes.set(new TextEncoder().encode(type), 4);
  bytes.set(payload, 8);
  return bytes;
};
const join = (...parts: Uint8Array[]) => {
  const bytes = new Uint8Array(parts.reduce((size, part) => size + part.length, 0));
  let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  return bytes;
};
const mp4 = join(
  box("ftyp", join(new TextEncoder().encode("isom"), new Uint8Array(4))),
  box("moov", box("trak", box("mdia", box("hdlr", join(new Uint8Array(8), new TextEncoder().encode("vide")))))),
  box("mdat", new Uint8Array([0])),
);

function request(passcode = "correct", bytes = mp4): Request {
  const form = new FormData();
  form.set("link", `https://music.youtube.com/watch?v=${ID}`);
  form.set("passcode", passcode);
  form.set("video", new File([bytes], "canvas.mp4", { type: "video/mp4" }));
  return new Request("https://worker.test/canvas", { method: "POST", body: form });
}

function env(passcode?: string, token?: string) {
  return {
    CANVAS_PASSCODE: passcode,
    CANVAS_GITHUB_TOKEN: token,
    CANVAS_LIMIT: { limit: async () => ({ success: true }) },
  };
}

test("upload route refuses missing secrets before accepting an upload", async () => {
  const response = await handleCanvasUpload(request(), env());
  assert.equal(response.status, 503);
});

test("upload route rejects a wrong passcode before contacting providers", async () => {
  let requests = 0;
  const fetcher = (async () => { requests++; return new Response(); }) as typeof fetch;
  const response = await handleCanvasUpload(request("wrong"), env("correct", "token"), fetcher);
  assert.equal(response.status, 403);
  assert.equal(requests, 0);
});

test("upload route cancels oversized multipart bodies without content-length", async () => {
  const boundary = "nuvx-test";
  const prefix = new TextEncoder().encode(`--${boundary}\r\nContent-Disposition: form-data; name="link"\r\n\r\nhttps://music.youtube.com/watch?v=${ID}\r\n--${boundary}\r\nContent-Disposition: form-data; name="passcode"\r\n\r\ncorrect\r\n--${boundary}\r\nContent-Disposition: form-data; name="video"; filename="canvas.mp4"\r\nContent-Type: video/mp4\r\n\r\n`);
  const suffix = new TextEncoder().encode(`\r\n--${boundary}--\r\n`);
  let cancelled = false;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  let step = 0;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(prefix);
    },
    pull(controller) {
      if (step++ === 0) {
        controller.enqueue(new Uint8Array(MAX_CANVAS_BYTES + 64 * 1024));
      } else if (step === 2) {
        controller.enqueue(suffix);
        timeout = setTimeout(() => controller.close(), 50);
      }
    },
    cancel() {
      cancelled = true;
      clearTimeout(timeout);
    },
  });
  const req = new Request("https://worker.test/canvas", {
    method: "POST",
    headers: { "content-type": `multipart/form-data; boundary=${boundary}` },
    body,
    duplex: "half",
  } as RequestInit);
  assert.equal(req.headers.get("content-length"), null);
  const response = await handleCanvasUpload(req, env("correct", "token"));
  assert.equal(response.status, 413);
  assert.equal(cancelled, true);
});

test("upload route rejects non-MP4 data", async () => {
  const fetcher = (async () => new Response()) as typeof fetch;
  const response = await handleCanvasUpload(request("correct", new TextEncoder().encode("not a video")), env("correct", "token"), fetcher);
  assert.equal(response.status, 415);
});

test("upload route commits a valid MP4 and updates the shared index", async () => {
  const calls: string[] = [];
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    calls.push(url);
    if (url.includes("youtube.com/oembed")) return Response.json({ title: "Falling Down", author_name: "Lil Peep - Topic" });
    if (!init?.method) return new Response("", { status: 404 });
    return new Response("{}", { status: 201 });
  }) as typeof fetch;

  const response = await handleCanvasUpload(request(), env("correct", "token"), fetcher);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, id: ID, replaced: false, shared: true });
  assert.equal(calls.length, 5);
  assert.ok(calls[0].endsWith(`/canvas/${ID}.mp4`));
  assert.ok(calls[1].endsWith(`/canvas/${ID}.mp4`));
  assert.ok(calls[2].includes("youtube.com/oembed"));
  assert.ok(calls[3].endsWith("/contents/core/canvas/index.json"));
  assert.ok(calls[4].endsWith("/contents/core/canvas/index.json"));
});
