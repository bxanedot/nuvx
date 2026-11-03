import { test } from "node:test";
import assert from "node:assert/strict";
import { isMp4, parseRange, samePasscode, videoIdFromLink } from "../src/canvas.ts";

const ID = "9qnqYL0eNNI";

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
  const mp4 = new Uint8Array([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0, 0]);
  assert.equal(isMp4(mp4), true);
  assert.equal(isMp4(new TextEncoder().encode("<html>not a video</html>")), false);
});

test("passcodes must match exactly and an unset passcode never matches", () => {
  assert.equal(samePasscode("open sesame", "open sesame"), true);
  assert.equal(samePasscode("open sesam", "open sesame"), false);
  assert.equal(samePasscode("", ""), false);
});

test("byte ranges for video players", () => {
  assert.deepEqual(parseRange("bytes=0-99", 1000), [0, 99]);
  assert.deepEqual(parseRange("bytes=900-", 1000), [900, 999]);
  assert.deepEqual(parseRange("bytes=-100", 1000), [900, 999]);
  assert.deepEqual(parseRange("bytes=0-5000", 1000), [0, 999]);
  assert.equal(parseRange("bytes=1000-", 1000), null);
  assert.equal(parseRange("bytes=0-1,5-9", 1000), null);
  assert.equal(parseRange(null, 1000), null);
});
