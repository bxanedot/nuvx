import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { getInnertube, getSignedInnertube, resolvePlayer, auth } = vi.hoisted(() => ({
  getInnertube: vi.fn(),
  getSignedInnertube: vi.fn(),
  resolvePlayer: vi.fn(),
  auth: { signedIn: false },
}));

vi.mock("@/playback/youtube/botguard", () => ({ botguard: { mint: vi.fn() } }));
vi.mock("@/extension/useYtAuth", () => ({ ytAuthSnapshot: () => auth }));
vi.mock("@/playback/youtube/innertube", () => ({ getInnertube, getSignedInnertube }));
vi.mock("@/playback/youtube/resolver", () => ({
  resolvePlayer,
  UnplayableError: class extends Error {
    constructor(message: string, readonly ageGated = false) { super(message); }
  },
  usesWebPo: () => false,
}));

import { fetchTrackAudio, readAudioResponse } from "./fetchAudio";
import { UnplayableError } from "@/playback/youtube/resolver";

describe("fetchTrackAudio size limit", () => {
  beforeEach(() => {
    getInnertube.mockResolvedValue({});
    resolvePlayer.mockResolvedValue({
      info: {
        streaming_data: {
          adaptive_formats: [{
            has_audio: true,
            has_video: false,
            url: "https://audio.example/file",
            mime_type: "audio/webm; codecs=opus",
            bitrate: 128000,
            itag: 251,
            is_drc: false,
            content_length: 0,
            decipher: async () => "https://audio.example/file",
          }],
        },
      },
      client: {},
      yt: { session: { player: {} } },
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    auth.signedIn = false;
  });

  it("rejects a response that declares more than 40 MB without buffering it", async () => {
    let cancelled = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        timeout = setTimeout(() => controller.close(), 25);
      },
      cancel() {
        cancelled = true;
        clearTimeout(timeout);
      },
    });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(body, { headers: { "content-length": String(40 * 1024 * 1024 + 1) } })));
    await expect(fetchTrackAudio("abcdefghijk")).rejects.toThrow("audio response exceeds byte limit");
    expect(cancelled).toBe(true);
  });

  it("cancels a response without content-length when streamed bytes cross the limit", async () => {
    let cancelled = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    let step = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (step++ === 0) controller.enqueue(new Uint8Array([1, 2, 3]));
        else if (step === 2) controller.enqueue(new Uint8Array([4, 5]));
        else timeout = setTimeout(() => controller.close(), 25);
      },
      cancel() {
        cancelled = true;
        clearTimeout(timeout);
      },
    });
    await expect(readAudioResponse(new Response(body), 4)).rejects.toThrow("audio response exceeds byte limit");
    expect(cancelled).toBe(true);
  });

  it("does not issue another range request after the signal aborts between chunks", async () => {
    const controller = new AbortController();
    const fetcher = vi.fn(async () => {
    controller.abort();
      return new Response(new Uint8Array(1024 * 1024));
    });
    vi.stubGlobal("fetch", fetcher);

    await expect(fetchTrackAudio("abcdefghijk", controller.signal)).rejects.toMatchObject({ name: "AbortError" });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("re-resolves at most twice after HTTP 403 responses", async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 403 }));
    vi.stubGlobal("fetch", fetcher);

    await expect(fetchTrackAudio("abcdefghijk")).rejects.toThrow("audio fetch HTTP 403 at byte 0");
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(resolvePlayer).toHaveBeenCalledTimes(3);
  });

  it("retries an age-gated result with the signed-in session only when signed in", async () => {
    auth.signedIn = true;
    vi.stubGlobal("fetch", vi.fn(async () => new Response(new Uint8Array([1]))));
    getSignedInnertube.mockResolvedValue({ signed: true });
    resolvePlayer.mockRejectedValueOnce(new UnplayableError("age restricted", true));

    await fetchTrackAudio("abcdefghijk");

    expect(getSignedInnertube).toHaveBeenCalledTimes(1);
    expect(resolvePlayer).toHaveBeenNthCalledWith(2, { signed: true }, "abcdefghijk", new Set(), true);
  });
});
