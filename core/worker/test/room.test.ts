import { test } from "node:test";
import assert from "node:assert/strict";
import { MAX_MEMBERS, ROOM_CODE, cleanName, emptyRoom, expectedPosition, handle, join, leave, roomCode, take } from "../src/room.ts";

const ID = "dQw4w9WgXcQ";

test("first member hosts; host leaving hands off to the next member", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  join(room, { id: "b", name: "B" }, 0);
  assert.equal(room.host, "a");
  const out = leave(room, "a");
  assert.equal(room.host, "b");
  assert.deepEqual(out.broadcast, { t: "members", host: "b", members: [{ id: "b", name: "B" }] });
  leave(room, "b");
  assert.equal(room.host, null);
});

test("only the host may change playback; state is broadcast with server time", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  join(room, { id: "b", name: "B" }, 0);
  const msg = JSON.stringify({ t: "state", trackId: ID, positionMs: 1000, playing: true, queue: [ID] });
  assert.deepEqual(handle(room, "b", msg, 5).reply, { t: "error", code: "not_host" });
  const out = handle(room, "a", msg, 5);
  assert.deepEqual(out.broadcast, { t: "state", s: 5, playback: { trackId: ID, positionMs: 1000, playing: true, updatedAt: 5, queue: [ID], title: undefined, artists: undefined } });
});

test("display text is optional and length-capped", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  const ok = handle(room, "a", JSON.stringify({ t: "state", trackId: ID, positionMs: 0, playing: true, title: "Song", artists: ["Band"] }), 1);
  assert.equal((ok.broadcast as any).playback.title, "Song");
  for (const bad of [{ title: "x".repeat(201) }, { artists: ["x".repeat(101)] }, { artists: Array(11).fill("a") }, { title: 5 }]) {
    assert.deepEqual(handle(room, "a", JSON.stringify({ t: "state", trackId: ID, positionMs: 0, playing: true, ...bad }), 1).reply, { t: "error", code: "bad_message" });
  }
});

test("upcoming queue preserves order and duplicates, rejects private IDs, and can be cleared", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  const state = (queue: string[]) => JSON.stringify({ t: "state", trackId: ID, positionMs: 1000, playing: false, queue });
  handle(room, "a", state(["aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa"]), 1);
  assert.deepEqual(room.playback.queue, ["aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa"]);
  const welcome = join(room, { id: "b", name: "B" }, 2).reply;
  assert.equal(welcome?.t, "welcome");
  if (welcome?.t === "welcome") assert.deepEqual(welcome.room.playback.queue, ["aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa"]);
  for (const bad of [["local:private"], ["file:///private.mp3"], Array(201).fill(ID)]) {
    assert.deepEqual(handle(room, "a", state(bad), 3).reply, { t: "error", code: "bad_message" });
  }
  handle(room, "a", state(Array(200).fill(ID)), 4);
  assert.equal(room.playback.queue.length, 200);
  handle(room, "a", state([]), 5);
  assert.deepEqual(room.playback.queue, []);
});

test("rejects malformed input and anything that is not a YouTube video id", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  for (const raw of ["{", "null", '{"t":"nope"}', JSON.stringify({ t: "state", trackId: "https://evil", positionMs: 0, playing: true }),
    JSON.stringify({ t: "state", trackId: ID, positionMs: -1, playing: true }), JSON.stringify({ t: "ping", c: "x" })]) {
    assert.deepEqual(handle(room, "a", raw, 0).reply, { t: "error", code: "bad_message" }, raw);
  }
});

test("queue display info is whitelisted, relayed to late joiners, and cleared for legacy updates", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  const info = { trackId: "aaaaaaaaaaa", title: "Upcoming", artists: ["Artist"] };
  const message = { t: "state", trackId: ID, positionMs: 0, playing: false, queue: [info.trackId], queueInfo: [{ ...info, localUri: "file:///private.mp3", artworkUrl: "https://untrusted" }] };
  handle(room, "a", JSON.stringify(message), 1);
  assert.deepEqual(room.playback.queueInfo, [info]);
  const welcome = join(room, { id: "b", name: "B" }, 2).reply;
  if (welcome?.t !== "welcome") assert.fail("expected welcome");
  assert.deepEqual(welcome.room.playback.queueInfo, [info]);
  assert.ok(!JSON.stringify(welcome).includes("private.mp3"));
  assert.ok(!JSON.stringify(welcome).includes("untrusted"));
  const { queueInfo, ...legacy } = message;
  handle(room, "a", JSON.stringify(legacy), 3);
  assert.equal(room.playback.queueInfo, undefined);
});

test("queue display info rejects unrelated IDs, duplicates, bad fields and oversized messages", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  const info = { trackId: "aaaaaaaaaaa", title: "Upcoming", artists: ["Artist"] };
  const base = { t: "state", trackId: ID, positionMs: 0, playing: false, queue: [info.trackId] };
  for (const queueInfo of [null, [null], [{ ...info, trackId: "local:private" }], [{ ...info, trackId: ID }], [info, info],
    [{ ...info, title: "x".repeat(201) }], [{ ...info, artists: Array(11).fill("a") }], [{ ...info, artists: ["x".repeat(101)] }],
    Array(201).fill(info)]) {
    assert.deepEqual(handle(room, "a", JSON.stringify({ ...base, queueInfo }), 1).reply, { t: "error", code: "bad_message" });
  }
  const queue = Array.from({ length: 100 }, (_, i) => `${i}`.padStart(11, "0"));
  const queueInfo = queue.map((trackId) => ({ trackId, title: "界".repeat(200), artists: ["艺".repeat(100)] }));
  const oversizedInfo = JSON.stringify({ ...base, queue, queueInfo });
  assert.ok(new TextEncoder().encode(oversizedInfo).byteLength < 131_072, "fixture must fit the outer message limit");
  assert.deepEqual(handle(room, "a", oversizedInfo, 1).reply, { t: "error", code: "bad_message" });
  assert.deepEqual(handle(room, "a", JSON.stringify({ ...base, unused: "x".repeat(131_072) }), 1).reply, { t: "error", code: "bad_message" });
});

test("handoff requires an existing member", () => {
  const room = emptyRoom(0);
  join(room, { id: "a", name: "A" }, 0);
  join(room, { id: "b", name: "B" }, 0);
  assert.deepEqual(handle(room, "a", '{"t":"handoff","to":"zz"}', 0).reply, { t: "error", code: "unknown_member" });
  handle(room, "a", '{"t":"handoff","to":"b"}', 0);
  assert.equal(room.host, "b");
});

test("ping echoes client time with server time for clock sync", () => {
  assert.deepEqual(handle(emptyRoom(0), "x", '{"t":"ping","c":123}', 999).reply, { t: "pong", c: 123, s: 999 });
});

test("expected position advances only while playing", () => {
  const p = { trackId: ID, positionMs: 1000, playing: true, updatedAt: 100, queue: [] };
  assert.equal(expectedPosition(p, 600), 1500);
  assert.equal(expectedPosition({ ...p, playing: false }, 600), 1000);
});

test("room is capped", () => {
  const room = emptyRoom(0);
  for (let i = 0; i < MAX_MEMBERS; i++) join(room, { id: `${i}`, name: "x" }, 0);
  assert.deepEqual(join(room, { id: "late", name: "x" }, 0).reply, { t: "error", code: "room_full" });
});

test("rate limiter drains and refills", () => {
  const b = { tokens: 2, at: 0 };
  assert.ok(take(b, 0) && take(b, 0));
  assert.equal(take(b, 0), false);
  assert.ok(take(b, 500)); // 2/s refill
});

test("codes and names are sanitised", () => {
  assert.match(roomCode((n) => new Uint8Array(n).map((_, i) => i * 41)), ROOM_CODE);
  assert.equal(cleanName("  \u0007Bo\nb "), "Bob");
  assert.equal(cleanName(""), "Listener");
  assert.equal(cleanName("x".repeat(99)).length, 32);
});
