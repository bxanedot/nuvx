// Runs against a live `wrangler dev` (default http://127.0.0.1:8787) with two real WebSocket clients.
import assert from "node:assert/strict";

const base = process.env.NUVX_LISTEN_URL ?? "http://127.0.0.1:8787";

function client(code: string, name: string) {
  const ws = new WebSocket(`${base.replace(/^http/, "ws")}/rooms/${code}/ws?name=${encodeURIComponent(name)}`);
  const inbox: any[] = [];
  const waiters: Array<() => void> = [];
  ws.addEventListener("message", (e) => { inbox.push(JSON.parse(e.data)); waiters.splice(0).forEach((w) => w()); });
  const next = async (t: string) => {
    for (;;) {
      const i = inbox.findIndex((m) => m.t === t);
      if (i >= 0) return inbox.splice(i, 1)[0];
      await Promise.race([new Promise<void>((r) => waiters.push(r)), new Promise((_, rej) => setTimeout(() => rej(new Error(`timeout waiting for ${t}`)), 5000))]);
    }
  };
  return { ws, next, open: new Promise((r) => ws.addEventListener("open", r)) };
}

const { code } = await (await fetch(`${base}/rooms`, { method: "POST" })).json();
assert.match(code, /^[A-Z2-9]{6}$/);
const ghost = new WebSocket(`${base.replace(/^http/, "ws")}/rooms/ZZZZZZ/ws`);
assert.equal(await new Promise((r) => { ghost.onopen = () => r("open"); ghost.onerror = () => r("refused"); }), "refused", "unknown room must refuse");

const host = client(code, "Host");
await host.open;
const hello = await host.next("welcome");
const guest = client(code, "Guest");
await guest.open;
const gHello = await guest.next("welcome");
assert.equal(gHello.room.host, hello.you, "first joiner hosts");

const sent = Date.now();
host.ws.send(JSON.stringify({ t: "ping", c: sent }));
const pong = await host.next("pong");
console.log(`clock offset ≈ ${pong.s - (sent + Date.now()) / 2} ms, rtt ${Date.now() - sent} ms`);

const queueInfo = [{ trackId: "aaaaaaaaaaa", title: "Upcoming", artists: ["Artist"] }];
host.ws.send(JSON.stringify({ t: "state", trackId: "dQw4w9WgXcQ", positionMs: 42000, playing: true, queue: ["aaaaaaaaaaa"], queueInfo }));
const synced = await guest.next("state");
assert.equal(synced.playback.trackId, "dQw4w9WgXcQ");
assert.equal(synced.playback.positionMs, 42000);
assert.deepEqual(synced.playback.queueInfo, queueInfo);

guest.ws.send(JSON.stringify({ t: "state", trackId: null, positionMs: 0, playing: false }));
assert.equal((await guest.next("error")).code, "not_host", "guest cannot control playback");

host.ws.close();
for (;;) {
  const m = await guest.next("members");
  if (m.members.length === 1) { assert.equal(m.host, gHello.you, "guest inherits host"); break; }
}
guest.ws.close();
console.log("e2e ok: create, join, clock sync, state sync, host-only control, host handoff");
