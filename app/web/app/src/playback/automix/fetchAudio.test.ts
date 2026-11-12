import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { getInnertube, getSignedInnertube, resolvePlayer } = vi.hoisted(() => ({
  getInnertube: vi.fn(),
  getSignedInnertube: vi.fn(),
  resolvePlayer: vi.fn(),
}));

vi.mock("@/playback/youtube/botguard", () => ({ botguard: { mint: vi.fn() } }));
vi.mock("@/extension/useYtAuth", () => ({ ytAuthSnapshot: () => ({ signedIn: false }) }));
vi.mock("@/playback/youtube/innertube", () => ({ getInnertube, getSignedInnertube }));
vi.mock("@/playback/youtube/resolver", () => ({
  resolvePlayer,
  UnplayableError: class extends Error { ageGated = false; },
  usesWebPo: () => false,
}));

import { fetchTrackAudio, readAudioResponse } from "./fetchAudio";

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
});
