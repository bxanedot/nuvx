import { test } from "node:test";
import assert from "node:assert/strict";
import { canvasKey, commitCanvas, isMp4, samePasscode, shareCanvas, videoIdFromLink } from "../src/canvas.ts";

const ID = "9qnqYL0eNNI";
const utf8Base64 = (value: string) => btoa(String.fromCharCode(...new TextEncoder().encode(value)));
const fromUtf8Base64 = (value: string) => new TextDecoder().decode(Uint8Array.from(atob(value), char => char.charCodeAt(0)));
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

test("song links and bare ids give the video id; anything else is rejected", () => {
  assert.equal(videoIdFromLink(`https://music.youtube.com/watch?v=${ID}&si=abc`), ID);
  assert.equal(videoIdFromLink(`https://www.youtube.com/watch?v=${ID}`), ID);
  assert.equal(videoIdFromLink(`https://youtu.be/${ID}?t=3`), ID);
  assert.equal(videoIdFromLink(`  ${ID} `), ID);
  assert.equal(videoIdFromLink("https://evil.example/watch?v=9qnqYL0eNNI"), null);
  assert.equal(videoIdFromLink("https://music.youtube.com/watch?v=../../x"), null);
  assert.equal(videoIdFromLink("not a link"), null);
});

test("only real MP4 data passes", () => {
  assert.equal(isMp4(mp4), true);
  assert.equal(isMp4(new TextEncoder().encode("<html>not a video</html>")), false);
});

test("passcodes must match exactly and an unset passcode never matches", () => {
  assert.equal(samePasscode("open sesame", "open sesame"), true);
  assert.equal(samePasscode("open sesam", "open sesame"), false);
  assert.equal(samePasscode("", ""), false);
});

test("commit creates a new file, and replaces an existing one with its sha", async () => {
  const calls: { method: string; body?: any }[] = [];
  const fake = (exists: boolean) => (async (_url: string, init?: RequestInit) => {
    calls.push({ method: init?.method ?? "GET", body: init?.body ? JSON.parse(init.body as string) : undefined });
    if (!init?.method) return new Response(exists ? JSON.stringify({ sha: "abc" }) : "{}", { status: exists ? 200 : 404 });
    return new Response("{}", { status: exists ? 200 : 201 });
  }) as typeof fetch;
  const bytes = new Uint8Array([1, 2, 3]);
  assert.deepEqual(await commitCanvas(ID, bytes, "t", fake(false)), { status: 201 });
  assert.equal(calls[1].body.sha, undefined);
  assert.equal(calls[1].body.content, "AQID");
  assert.deepEqual(await commitCanvas(ID, bytes, "t", fake(true)), { status: 200 });
  assert.equal(calls[3].body.sha, "abc");
});

test("canvas keys normalize title and Topic artist names", () => {
  assert.equal(canvasKey("  Falling   Down ", "Lil Peep - Topic"), "falling down|lil peep");
});

test("sharing merges the existing index and stores the oEmbed song", async () => {
  const calls: { url: string; method: string; body?: any }[] = [];
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? "GET", body: init?.body ? JSON.parse(init.body as string) : undefined });
    if (url.includes("youtube.com/oembed")) return Response.json({ title: "Björk", author_name: "Beyoncé - Topic" });
    if (!init?.method) return Response.json({ sha: "sha1", content: utf8Base64(JSON.stringify({ "niño|beyoncé": "abcdefghijk" })) });
    return new Response("{}", { status: 200 });
  }) as typeof fetch;
  assert.equal(await shareCanvas(ID, "token", fetcher), true);
  assert.deepEqual(JSON.parse(fromUtf8Base64(calls[2].body.content)), { "niño|beyoncé": "abcdefghijk", "björk|beyoncé": ID });
  assert.equal(calls[2].body.sha, "sha1");
});

test("sharing creates the index when it does not exist", async () => {
  const calls: { url: string; method: string; body?: any }[] = [];
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? "GET", body: init?.body ? JSON.parse(init.body as string) : undefined });
    if (url.includes("youtube.com/oembed")) return Response.json({ title: "Falling Down", author_name: "Lil Peep - Topic" });
    if (!init?.method) return new Response("", { status: 404 });
    return new Response("{}", { status: 201 });
  }) as typeof fetch;
  assert.equal(await shareCanvas(ID, "token", fetcher), true);
  assert.equal(calls[2].body.sha, undefined);
  assert.deepEqual(JSON.parse(atob(calls[2].body.content)), { "falling down|lil peep": ID });
});

test("sharing refetches and merges the index after a sha conflict", async () => {
  const puts: any[] = [];
  let reads = 0;
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("youtube.com/oembed")) return Response.json({ title: "Björk", author_name: "Beyoncé - Topic" });
    if (!init?.method) {
      reads++;
      const index = reads === 1
        ? { "old|song": "abcdefghijk" }
        : { "old|song": "abcdefghijk", "other|song": "lmnopqrstuv" };
      return Response.json({ sha: reads === 1 ? "sha1" : "sha2", content: utf8Base64(JSON.stringify(index)) });
    }
    const body = JSON.parse(init.body as string);
    puts.push(body);
    return new Response("{}", { status: puts.length === 1 ? 409 : 200 });
  }) as typeof fetch;
  assert.equal(await shareCanvas(ID, "token", fetcher), true);
  assert.equal(reads, 2);
  assert.equal(puts.length, 2);
  assert.equal(puts[0].sha, "sha1");
  assert.equal(puts[1].sha, "sha2");
  assert.deepEqual(JSON.parse(fromUtf8Base64(puts[1].content)), {
    "old|song": "abcdefghijk",
    "other|song": "lmnopqrstuv",
    "björk|beyoncé": ID,
  });
});

test("sharing stops after bounded index conflict retries", async () => {
  let reads = 0;
  let writes = 0;
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("youtube.com/oembed")) return Response.json({ title: "Björk", author_name: "Beyoncé - Topic" });
    if (!init?.method) {
      reads++;
      return Response.json({ sha: `sha${reads}`, content: utf8Base64("{}") });
    }
    writes++;
    return new Response("{}", { status: 409 });
  }) as typeof fetch;
  assert.equal(await shareCanvas(ID, "token", fetcher), false);
  assert.equal(reads, 3);
  assert.equal(writes, 3);
});
