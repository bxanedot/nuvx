import { test } from "node:test";
import assert from "node:assert/strict";
import { handleCanvasUpload } from "../src/canvas-route.ts";

const ID = "9qnqYL0eNNI";
const mp4 = new Uint8Array([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0, 0]);

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
  assert.ok(calls[3].endsWith("/canvas/index.json"));
  assert.ok(calls[4].endsWith("/canvas/index.json"));
});
