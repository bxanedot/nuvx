import { test } from "node:test";
import assert from "node:assert/strict";
import * as P from "../src/party.ts";

let seed = 1;
const random = (n: number) => Uint8Array.from({ length: n }, () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) & 0xff);
const req = (deviceId: string, extra: Partial<P.JoinRequest> = {}): P.JoinRequest => ({ userId: `u-${deviceId}`, deviceId, displayName: `Name ${deviceId}`, ...extra });
const track = (videoId: string, extra: Partial<P.Track> = {}): P.Track => ({ videoId, title: videoId, artist: "a", fromAutoplay: false, ...extra });
const none = () => false;

function party(now = 1000) {
  const p = P.newParty("ABC123", now);
  const host = P.join(p, req("h"), random, now) as P.Member;
  const guest = P.join(p, req("g"), random, now + 1) as P.Member;
  return { p, host, guest };
}

test("codes: alphabet, length, normalisation", () => {
  for (let i = 0; i < 50; i++) assert.ok(P.isValidCode(P.newCode(random)));
  assert.equal(P.normaliseCode(" ab-c1o i l "), "ABC1011");
  assert.equal(P.normaliseCode("ab12cd"), "AB12CD");
  assert.ok(!P.isValidCode("ABCDE"));
  assert.ok(!P.isValidCode("ABCDEI"));
});

test("join request validation", () => {
  assert.equal((P.validateJoin(null) as P.Fail).error, "invalid_json");
  assert.equal((P.validateJoin({ userId: "", deviceId: "d", displayName: "n" }) as P.Fail).status, 422);
  assert.equal((P.validateJoin({ ...req("d"), displayName: "x".repeat(81) }) as P.Fail).error, "validation_error");
  assert.equal((P.validateJoin({ ...req("d"), avatarUrl: "javascript:x" }) as P.Fail).error, "validation_error");
  assert.equal((P.validateJoin({ ...req("d"), maxMembers: 11 }) as P.Fail).error, "validation_error");
  assert.deepEqual(P.validateJoin({ ...req("d"), displayName: "  Kai  ", avatarUrl: " ", autoplayEnabled: true }), { userId: "u-d", deviceId: "d", displayName: "Kai", autoplayEnabled: true });
});

test("track decoding rejects non-finite durations", () => {
  const track = P.trackFromWire(JSON.parse('{"videoId":"abcdefghijk","durationMs":1e309}'));
  assert.equal(track?.durationMs, undefined);
  assert.equal(P.trackFromWire({ videoId: "abcdefghijk", durationMs: 123.9 })?.durationMs, 123);
});

test("join: first is host, rejoin rotates token, full party refused", () => {
  const { p, host, guest } = party();
  assert.ok(host.isHost && !guest.isHost);
  const old = guest.token;
  const again = P.join(p, req("g", { displayName: "New" }), random, 2000) as P.Member;
  assert.equal(again.memberId, guest.memberId);
  assert.notEqual(again.token, old);
  assert.equal(P.authenticate(p, old), null);
  assert.equal(P.authenticate(p, again.token)?.memberId, guest.memberId);
  p.maxMembers = 2;
  assert.equal((P.join(p, req("x"), random, 3000) as P.Fail).error, "party_full");
});

test("remove hands host to the longest-present member", () => {
  const { p, host, guest } = party();
  const third = P.join(p, req("t"), random, 5000) as P.Member;
  P.remove(p, host.memberId);
  assert.ok(guest.isHost && !third.isHost);
});

test("play/pause/seek anchor with the play lead", () => {
  const { p, host } = party();
  assert.ok(P.applyControl(p, host, { action: "play", positionMs: 1000 }, 10_000).ok);
  assert.equal(p.playback.anchorMs, 10_000 + P.PLAY_LEAD_MS);
  assert.equal(P.positionAt(p.playback, 10_000), 1000, "holds before the anchor");
  assert.equal(P.positionAt(p.playback, 12_350), 3000);
  P.applyControl(p, host, { action: "pause" }, 12_350);
  assert.equal(p.playback.positionMs, 3000);
  assert.equal(P.positionAt(p.playback, 99_999), 3000);
  assert.equal((P.applyControl(p, host, { action: "seek" }, 1) as { error: string }).error, "missing_position");
});

test("setTrack/setQueue pick the copy nearest the current index", () => {
  const { p, host } = party();
  P.applyControl(p, host, { action: "setQueue", queue: ["a", "b", "a", "c"].map((v) => track(v)), queueIndex: 2 }, 1);
  assert.equal(p.playback.queueIndex, 2);
  P.applyControl(p, host, { action: "setTrack", track: track("a"), queueIndex: 0 }, 2);
  assert.equal(p.playback.queueIndex, 0);
  P.applyControl(p, host, { action: "next" }, 3);
  assert.equal(p.playback.track?.videoId, "b");
  assert.equal(p.playback.startedByName, host.displayName);
  assert.equal(P.nearestIndexOf(["x", "a", "y", "a"].map((v) => track(v)), "a", 2), 3);
});

test("queue limits, autoplay de-dupe, remove/clear/move", () => {
  const { p, host } = party();
  P.applyControl(p, host, { action: "setTrack", track: track("now") }, 1);
  P.applyControl(p, host, { action: "setQueue", queue: [track("now")], queueIndex: 0 }, 1);
  const ap = { action: "queueAdd", tracks: [track("s1", { fromAutoplay: true }), track("s1", { fromAutoplay: true })] };
  assert.ok(P.applyControl(p, host, ap, 2).ok);
  assert.ok(P.applyControl(p, host, ap, 3).ok, "repeat batch is not a refusal");
  assert.equal(p.playback.queue.length, 2);
  P.applyControl(p, host, { action: "queueAdd", track: track("next"), playNext: true }, 4);
  assert.deepEqual(p.playback.queue.map((t) => t.videoId), ["now", "next", "s1"]);
  assert.equal((P.applyControl(p, host, { action: "queueMove", fromIndex: 2, toIndex: 0 }, 5) as { error: string }).error, "invalid_move");
  P.applyControl(p, host, { action: "queueMove", fromIndex: 2, toIndex: 1 }, 5);
  assert.deepEqual(p.playback.queue.map((t) => t.videoId), ["now", "s1", "next"]);
  assert.equal((P.applyControl(p, host, { action: "queueRemove", videoId: "now" }, 6) as { error: string }).error, "not_found");
  P.applyControl(p, host, { action: "queueRemove", index: 1 }, 6);
  assert.ok(P.applyControl(p, host, { action: "queueClear" }, 7).ok);
  assert.equal((P.applyControl(p, host, { action: "queueClear" }, 8) as { error: string }).error, "no_upcoming");
  const many = Array.from({ length: 30 }, (_, i) => track(`t${i}`));
  P.applyControl(p, host, { action: "queueAdd", tracks: many }, 9);
  assert.equal(p.playback.queue.length, 1 + P.MAX_UPCOMING);
  assert.equal((P.applyControl(p, host, { action: "queueAdd", track: track("z") }, 10) as { error: string }).error, "queue_full");
});

test("host-only control, host-only settings, kick", () => {
  const { p, host, guest } = party();
  assert.ok(P.applyControl(p, guest, { action: "play" }, 1).ok, "guests control by default");
  assert.equal((P.applyControl(p, guest, { action: "setHostOnlyControl", enabled: true }, 2) as { error: string }).error, "host_only");
  assert.ok(P.applyControl(p, host, { action: "setHostOnlyControl", enabled: true }, 3).ok);
  assert.equal((P.applyControl(p, guest, { action: "pause" }, 4) as { error: string }).error, "host_only");
  assert.equal((P.applyControl(p, host, { action: "setMaxMembers", maxMembers: 1 }, 5) as { error: string }).error, "invalid_capacity");
  assert.equal((P.applyControl(p, host, { action: "kick", memberId: host.memberId }, 6) as { error: string }).error, "invalid_member");
  assert.deepEqual(P.applyControl(p, host, { action: "kick", memberId: guest.memberId }, 7), { ok: true, kicked: guest.memberId });
  assert.equal(p.members.length, 1);
  assert.equal((P.applyControl(p, host, { action: "warp" }, 8) as { error: string }).error, "unknown_action");
});

test("expiry: grace, empty TTL, max age, next alarm", () => {
  const { p, guest } = party(0);
  const online = (id: string) => id !== guest.memberId;
  P.refreshEmptiness(p, online, 0);
  assert.equal(p.emptySinceMs, null);
  assert.deepEqual(P.expiredMembers(p, online, P.DISCONNECT_GRACE_MS + 2).map((m) => m.memberId), [guest.memberId]);
  assert.equal(P.nextExpiry(p, online), guest.lastSeenMs + P.DISCONNECT_GRACE_MS + 1);
  P.refreshEmptiness(p, none, 10);
  assert.ok(!P.isExpired(p, 10 + P.EMPTY_PARTY_TTL_MS));
  assert.ok(P.isExpired(p, 11 + P.EMPTY_PARTY_TTL_MS));
  P.refreshEmptiness(p, () => true, 20);
  assert.ok(P.isExpired(p, P.PARTY_MAX_AGE_MS + 1));
});

test("wire shapes and token buckets", () => {
  const { p, host } = party();
  const wire = P.partyWire(p, (id) => id === host.memberId, 5);
  assert.deepEqual(Object.keys(wire), ["code", "createdAtMs", "maxMembers", "hostOnlyControl", "members", "playback", "queue", "serverMs"]);
  assert.equal(wire.members[0].connected, true);
  assert.ok(!("token" in wire.members[0]) && !("deviceId" in wire.members[0]));
  assert.deepEqual(P.previewWire(p).members.map((m) => m.isHost), [true, false]);
  const b = P.freshBuckets(0);
  let n = 0;
  while (P.spendControl(b.control, 0)) n++;
  assert.equal(n, 25);
  assert.ok(P.spendControl(b.control, 40), "refills at 25/s");
  assert.equal(P.activityFrame(host, { action: "queueAdd", tracks: [track("x"), track("y")] }, 1).detail, 'Added "x" and 1 more to the queue');
});
