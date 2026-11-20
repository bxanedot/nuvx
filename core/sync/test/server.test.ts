import assert from "node:assert/strict";
import { test } from "node:test";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp, openDb } from "../src/server.ts";

function app(start = 1_000_000, maxDbBytes?: number) {
  let t = start;
  const a = createApp(openDb(":memory:"), () => t, maxDbBytes);
  const call = (method: string, path: string, token?: string, body: Record<string, unknown> = {}) => {
    const url = new URL(path, "http://local");
    return a.handle(method, url.pathname, url.searchParams, token && `Bearer ${token}`, body) as { status: number; body: any };
  };
  return { call, advance: (ms: number) => { t += ms; } };
}
const newSpace = (a: ReturnType<typeof app>, name = "Phone") => a.call("POST", "/v1/spaces", undefined, { deviceName: name }).body;
const op = (id: string, payload: unknown = { n: 1 }) => ({ id, kind: "playlist.addTrack", entity: "playlist:1", payload });

test("health detects unavailable SQLite rather than reporting HTTP-only liveness", () => {
  const live = openDb(":memory:");
  const service = createApp(live);
  const health = () => service.handle("GET", "/healthz", new URLSearchParams(), undefined, {}) as { status: number; body: any };
  assert.equal(health().status, 200);
  live.close();
  assert.equal(health().status, 503, "closed SQLite handle must be unhealthy");
  assert.equal(health().body.error, "database_unavailable");

  const missingSchema = new DatabaseSync(":memory:");
  const broken = createApp(missingSchema).handle("GET", "/healthz", new URLSearchParams(), undefined, {}) as { status: number };
  assert.equal(broken.status, 503, "the health check must query the actual spaces table");
  missingSchema.close();
});

test("everything except health, new spaces and pairing needs a device token", () => {
  const a = app();
  assert.equal(a.call("GET", "/healthz").status, 200);
  for (const [m, p] of [["GET", "/v1/devices"], ["POST", "/v1/ops"], ["GET", "/v1/ops"], ["GET", "/v1/export"], ["DELETE", "/v1/space"]]) {
    assert.equal(a.call(m, p).status, 401, `${m} ${p}`);
    assert.equal(a.call(m, p, "not-a-token").status, 401, `${m} ${p} bad token`);
  }
});

test("pushing the same ops twice stores them once and returns the same sequence numbers", () => {
  const a = app();
  const me = newSpace(a);
  const first = a.call("POST", "/v1/ops", me.token, { ops: [op("a"), op("b")] });
  const again = a.call("POST", "/v1/ops", me.token, { ops: [op("a"), op("b"), op("c")] });
  assert.equal(first.status, 200);
  assert.deepEqual(again.body.seqs.slice(0, 2), first.body.seqs);
  const pulled = a.call("GET", "/v1/ops?after=0", me.token).body.ops;
  assert.deepEqual(pulled.map((o: any) => o.id), ["a", "b", "c"]);
  assert.deepEqual(a.call("GET", `/v1/ops?after=${first.body.seqs[1]}`, me.token).body.ops.map((o: any) => o.id), ["c"]);
});

test("two devices see one total order, and spaces are isolated from each other", () => {
  const a = app();
  const phone = newSpace(a), stranger = newSpace(a, "Other");
  const tv = pair(a, phone.token, "TV");
  a.call("POST", "/v1/ops", phone.token, { ops: [op("p1")] });
  a.call("POST", "/v1/ops", tv.token, { ops: [op("t1")] });
  a.call("POST", "/v1/ops", stranger.token, { ops: [op("s1")] });
  const seen = (token: string) => a.call("GET", "/v1/ops", token).body.ops.map((o: any) => o.id);
  assert.deepEqual(seen(phone.token), ["p1", "t1"]);
  assert.deepEqual(seen(tv.token), ["p1", "t1"]);
  assert.deepEqual(seen(stranger.token), ["s1"]);
});

function pair(a: ReturnType<typeof app>, approver: string, name: string) {
  const started = a.call("POST", "/v1/pairings", undefined, { deviceName: name }).body;
  assert.equal(a.call("POST", `/v1/pairings/${started.code}/claim`, undefined, { secret: started.secret }).status, 202, "pending before approval");
  assert.equal(a.call("POST", `/v1/pairings/${started.code}/approve`, approver).body.deviceName, name);
  const claimed = a.call("POST", `/v1/pairings/${started.code}/claim`, undefined, { secret: started.secret });
  assert.equal(claimed.status, 200);
  return claimed.body;
}

test("pairing: needs approval and the secret, works once, and expires", () => {
  const a = app();
  const phone = newSpace(a);
  const started = a.call("POST", "/v1/pairings", undefined, { deviceName: "TV" }).body;
  const claim = (secret: string) => a.call("POST", `/v1/pairings/${started.code}/claim`, undefined, { secret });
  assert.equal(a.call("POST", `/v1/pairings/${started.code}/approve`).status, 401, "approval needs a trusted device");
  a.call("POST", `/v1/pairings/${started.code}/approve`, phone.token);
  assert.equal(claim("wrong").status, 401, "the code alone is not enough");
  assert.equal(claim(started.secret).status, 200);
  assert.equal(claim(started.secret).status, 404, "a code works once");

  const late = a.call("POST", "/v1/pairings", undefined, { deviceName: "Late" }).body;
  a.advance(2 * 60_000 + 1);
  assert.equal(a.call("POST", `/v1/pairings/${late.code}/approve`, phone.token).status, 404, "expired");
});

test("pairing: the first approval wins, so a second space cannot take an approved device", () => {
  const a = app();
  const owner = newSpace(a), stranger = newSpace(a, "Stranger");
  const started = a.call("POST", "/v1/pairings", undefined, { deviceName: "TV" }).body;
  assert.equal(a.call("POST", `/v1/pairings/${started.code}/approve`, owner.token).status, 200);
  assert.equal(a.call("POST", `/v1/pairings/${started.code}/approve`, stranger.token).status, 409);
  const tv = a.call("POST", `/v1/pairings/${started.code}/claim`, undefined, { secret: started.secret }).body;
  assert.equal(tv.spaceId, owner.spaceId);
});

test("pairing: the device cap also holds when several approved codes are claimed", () => {
  const a = app();
  const phone = newSpace(a);
  for (let i = 1; i < 19; i++) pair(a, phone.token, `D${i}`); // 19 devices
  const starts = [0, 1].map((i) => a.call("POST", "/v1/pairings", undefined, { deviceName: `Late${i}` }).body);
  for (const s of starts) assert.equal(a.call("POST", `/v1/pairings/${s.code}/approve`, phone.token).status, 200);
  const claim = (s: { code: string; secret: string }) => a.call("POST", `/v1/pairings/${s.code}/claim`, undefined, { secret: s.secret }).status;
  assert.equal(claim(starts[0]), 200);
  assert.equal(claim(starts[1]), 409);
  assert.equal(a.call("GET", "/v1/devices", phone.token).body.devices.length, 20);
});

test("a revoked device loses access at once; others keep theirs", () => {
  const a = app();
  const phone = newSpace(a);
  const tv = pair(a, phone.token, "TV");
  assert.equal(a.call("GET", "/v1/devices", phone.token).body.devices.length, 2);
  assert.equal(a.call("DELETE", `/v1/devices/${tv.deviceId}`, phone.token).status, 200);
  assert.equal(a.call("GET", "/v1/ops", tv.token).status, 401);
  assert.equal(a.call("GET", "/v1/ops", phone.token).status, 200);
});

test("revoke all other sessions leaves current device authenticated and invalidates others", () => {
  const a = app();
  const phone = newSpace(a), laptop = pair(a, phone.token, "Laptop"), tv = pair(a, phone.token, "TV");
  const stranger = newSpace(a, "Other space");
  const approved = a.call("POST", "/v1/pairings", undefined, { deviceName: "Pending" }).body;
  assert.equal(a.call("POST", `/v1/pairings/${approved.code}/approve`, phone.token).status, 200);
  assert.equal(a.call("POST", "/v1/devices/revoke-others", undefined, { confirm: true }).status, 401);
  assert.equal(a.call("POST", "/v1/devices/revoke-others", stranger.token, { confirm: false }).status, 422);
  assert.equal(a.call("POST", "/v1/devices/revoke-others", phone.token).status, 422);
  assert.equal(a.call("GET", "/v1/devices", laptop.token).status, 200);
  const revoked = a.call("POST", "/v1/devices/revoke-others", phone.token, { confirm: true });
  assert.deepEqual(revoked, { status: 200, body: { ok: true, revoked: 2 } });
  assert.equal(a.call("GET", "/v1/ops", laptop.token).status, 401);
  assert.equal(a.call("GET", "/v1/ops", tv.token).status, 401);
  assert.equal(a.call("GET", "/v1/ops", phone.token).status, 200);
  assert.equal(a.call("GET", "/v1/devices", phone.token).body.devices.length, 1);
  assert.equal(a.call("GET", "/v1/ops", stranger.token).status, 200);
  assert.equal(a.call("POST", `/v1/pairings/${approved.code}/claim`, undefined, { secret: approved.secret }).status, 404);
  assert.equal(a.call("POST", "/v1/devices/revoke-others", phone.token, { confirm: true }).body.revoked, 0);
});

test("export returns everything; deleting the space removes data and access", () => {
  const a = app();
  const me = newSpace(a);
  a.call("POST", "/v1/ops", me.token, { ops: [op("a", { title: "Mix" })] });
  assert.deepEqual(a.call("GET", "/v1/export", me.token).body.ops[0].payload, { title: "Mix" });
  assert.equal(a.call("DELETE", "/v1/space", me.token).status, 200);
  assert.equal(a.call("GET", "/v1/export", me.token).status, 401);
});

test("confirmed Sync operation deletion removes only the owner's remote history", () => {
  const a = app();
  const owner = newSpace(a), companion = pair(a, owner.token, "Laptop"), outsider = newSpace(a, "Other space");
  assert.equal(a.call("POST", "/v1/ops", owner.token, { ops: [op("owner1"), op("owner2")] }).status, 200);
  assert.equal(a.call("POST", "/v1/ops", outsider.token, { ops: [op("foreign")] }).status, 200);
  assert.equal(a.call("DELETE", "/v1/ops", undefined, { confirm: "delete-operations" }).status, 401);
  assert.equal(a.call("DELETE", "/v1/ops", "unknown", { confirm: "delete-operations" }).status, 401);
  assert.equal(a.call("DELETE", "/v1/ops", owner.token).status, 422);
  assert.equal(a.call("DELETE", "/v1/ops", owner.token, { confirm: true }).status, 422);
  assert.equal(a.call("GET", "/v1/export", owner.token).body.ops.length, 2);
  assert.deepEqual(a.call("DELETE", "/v1/ops", owner.token, { confirm: "delete-operations" }), {
    status: 200, body: { ok: true, deleted: 2 },
  });
  assert.deepEqual(a.call("GET", "/v1/export", companion.token).body.ops, []);
  assert.deepEqual(a.call("GET", "/v1/export", outsider.token).body.ops.map((o: any) => o.id), ["foreign"]);
  assert.equal(a.call("GET", "/v1/devices", owner.token).body.devices.length, 2, "device sessions must remain");
  assert.equal(a.call("POST", "/v1/ops", companion.token, { ops: [op("after")] }).status, 200);
  assert.deepEqual(a.call("GET", "/v1/export", owner.token).body.ops.map((o: any) => o.id), ["after"]);
});

test("a space stops at its storage limit, and export pages like a pull", () => {
  const a = app();
  const me = newSpace(a);
  const big = "x".repeat(30_000);
  let status = 200, pushed = 0;
  for (let batch = 0; status === 200 && batch < 10; batch++) {
    status = a.call("POST", "/v1/ops", me.token, { ops: Array.from({ length: 100 }, (_, i) => op(`b${batch}-${i}`, big)) }).status;
    if (status === 200) pushed += 100;
  }
  assert.equal(status, 413);
  assert.equal(pushed, 600, "20 MB holds six batches of 100 x 30 KB");
  const first = a.call("GET", "/v1/export", me.token).body;
  assert.equal(first.ops.length, 500);
  assert.equal(first.more, true);
  const rest = a.call("GET", `/v1/export?after=${first.ops.at(-1).seq}`, me.token).body;
  assert.equal(rest.ops.length, 100);
  assert.equal(rest.more, false);
});

test("bad input is refused: oversize payloads, empty and oversize batches, missing fields", () => {
  const a = app();
  const me = newSpace(a);
  const push = (ops: unknown) => a.call("POST", "/v1/ops", me.token, { ops }).status;
  assert.equal(push([]), 422);
  assert.equal(push(Array.from({ length: 201 }, (_, i) => op(`x${i}`))), 422);
  assert.equal(push([op("big", "x".repeat(33 * 1024))]), 422);
  assert.equal(push([{ id: "a", kind: "k" }]), 422);
  assert.equal(a.call("POST", "/v1/spaces", undefined, { deviceName: "" }).status, 422);
});

test("reopening a database keeps its data and does not rerun migrations", () => {
  const path = join(mkdtempSync(join(tmpdir(), "nuvx-sync-")), "t.db");
  let db: DatabaseSync = openDb(path);
  db.prepare("INSERT INTO spaces (id, created_ms) VALUES ('s', 1)").run();
  db.close();
  db = openDb(path);
  assert.equal((db.prepare("SELECT COUNT(*) AS n FROM spaces").get() as { n: number }).n, 1);
  db.close();
});

test("schema rollback guard preserves a newer database while v1 remains readable", () => {
  const path = join(mkdtempSync(join(tmpdir(), "nuvx-sync-schema-")), "compat.db");
  let db = openDb(path);
  db.prepare("INSERT INTO spaces (id, created_ms) VALUES ('existing', 7)").run();
  assert.equal((db.prepare("PRAGMA user_version").get() as { user_version: number }).user_version, 1);
  db.close();

  // This is a persisted v1 fixture: reopen as a regular older deployment.
  db = openDb(path);
  assert.equal((db.prepare("SELECT created_ms FROM spaces WHERE id='existing'").get() as { created_ms: number }).created_ms, 7);
  // Simulate a later migration; older code must refuse, not rewrite that database.
  db.exec("PRAGMA user_version = 2");
  db.close();
  assert.throws(() => openDb(path), /schema v2 is newer than supported v1/);

  const raw = new DatabaseSync(path);
  assert.equal((raw.prepare("SELECT created_ms FROM spaces WHERE id='existing'").get() as { created_ms: number }).created_ms, 7);
  assert.equal((raw.prepare("PRAGMA user_version").get() as { user_version: number }).user_version, 2);
  raw.close();
});

test("a full server refuses new spaces and pushes, but still serves reads", () => {
  const a = app(1_000_000, 1);
  assert.equal(a.call("POST", "/v1/spaces", undefined, { deviceName: "Phone" }).status, 507);
  const roomy = app(1_000_000, 300_000);
  const me = newSpace(roomy);
  let status = 200;
  for (let i = 0; status === 200 && i < 20; i++) status = roomy.call("POST", "/v1/ops", me.token, { ops: [op(`o${i}`, "x".repeat(30_000))] }).status;
  assert.equal(status, 507);
  assert.equal(roomy.call("GET", "/v1/ops", me.token).status, 200);
  assert.equal(roomy.call("POST", "/v1/spaces", undefined, { deviceName: "Laptop" }).status, 507);
});

test("a huge whitespace-padded bearer header is refused quickly", () => {
  const a = app();
  const started = performance.now();
  assert.equal(a.call("GET", "/v1/devices", `${" ".repeat(50_000)}\n`).status, 401);
  assert.equal(a.call("GET", "/v1/devices", `Bearer ${" 	".repeat(50_000)}x`).status, 401, "over the length cap");
  assert.ok(performance.now() - started < 1_000, "the auth pattern must not backtrack on long headers");
});
