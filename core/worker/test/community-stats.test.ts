import { test } from "node:test";
import assert from "node:assert/strict";
import { communitySnapshot } from "../src/community-stats.ts";
import { readRequestBytes } from "../src/request-body.ts";
import { MAX_BYTES } from "../src/party.ts";

test("publisher stats accept a bounded, typed snapshot without changing valid counts", () => {
  const published = communitySnapshot({
    memberCount: 125, activeListeners: 3,
    listeners: [{ displayName: " Speaker ", minutes: 4 }, { displayName: "Guest", minutes: 8 }, { displayName: "New", minutes: 0 }],
  }, 1234);
  assert.deepEqual(published, {
    memberCount: 125, activeListeners: 3,
    listeners: [{ displayName: "Guest", minutes: 8 }, { displayName: "Speaker", minutes: 4 }],
    updatedAt: 1234,
  });
});

test("publisher stats reject malformed, coerced and oversized schemas", () => {
  const base = { memberCount: 10, activeListeners: 1, listeners: [{ displayName: "Kai", minutes: 6 }] };
  for (const bad of [
    null, [], { ...base, memberCount: "10" }, { ...base, activeListeners: null },
    { ...base, memberCount: false }, { ...base, memberCount: 1e309 },
    { ...base, listeners: Array.from({ length: 11 }, () => ({ displayName: "x", minutes: 1 })) },
    { ...base, listeners: [{ displayName: "x", minutes: "1" }] },
    { ...base, listeners: [{ displayName: "", minutes: 1 }] },
    { ...base, listeners: [{ displayName: "x".repeat(81), minutes: 1 }] },
  ]) assert.equal(communitySnapshot(bad), null);
});

test("publisher request body is bounded before JSON parsing or storage", async () => {
  const req = (body: string) => new Request("https://worker.test/api/community", { method: "POST", body });
  const valid = JSON.stringify({ memberCount: 1, activeListeners: 0, listeners: [] });
  assert.ok(await readRequestBytes(req(valid), MAX_BYTES));
  assert.equal(await readRequestBytes(req("x".repeat(MAX_BYTES + 1)), MAX_BYTES), null);
});
