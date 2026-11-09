// Runs against a live `wrangler dev` (default http://127.0.0.1:8787) with real WebSocket clients, using the
// browser path (token as subprotocol). Covers the "done when" list in docs/designs/listen-together-worker.md.
import assert from "node:assert/strict";

const base = process.env.NUVX_LISTEN_URL ?? "http://127.0.0.1:8787";
const post = async (path: string, body: unknown, token?: string) => {
  const r = await fetch(`${base}${path}`, { method: "POST", headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json() as any };
};
const joinReq = (id: string) => ({ userId: `user-${id}`, deviceId: `device-${id}`, displayName: id });

function client(code: string, token: string) {
  const ws = new WebSocket(`${base.replace(/^http/, "ws")}/ws/parties/${code}`, ["nuvx.v1", `nuvx.token.${token}`]);
  const inbox: any[] = [];
  const waiters: Array<() => void> = [];
  let closed = false;
  ws.addEventListener("message", (e) => { inbox.push(JSON.parse(e.data)); waiters.splice(0).forEach((w) => w()); });
  ws.addEventListener("close", () => { closed = true; waiters.splice(0).forEach((w) => w()); });
  const next = async (type: string, match: (m: any) => boolean = () => true) => {
    for (;;) {
      const i = inbox.findIndex((m) => m.type === type && match(m));
      if (i >= 0) return inbox.splice(i, 1)[0];
      if (closed) throw new Error(`closed while waiting for ${type}`);
      await Promise.race([new Promise<void>((r) => waiters.push(r)), new Promise((_, rej) => setTimeout(() => rej(new Error(`timeout waiting for ${type}`)), 5000))]);
    }
  };
  const send = (frame: unknown) => ws.send(JSON.stringify(frame));
  return { ws, next, send, open: new Promise((r) => ws.addEventListener("open", r)), get closed() { return closed; } };
}

const created = await post("/api/parties", joinReq("Host"));
assert.equal(created.status, 201);
const code: string = created.body.code;
assert.match(code, /^[0-9A-HJKMNP-Z]{6}$/);
assert.equal(created.body.you.isHost, true);

assert.equal((await post(`/api/parties/ZZZZZZ/join`, joinReq("x"))).status, 404, "unknown party");
assert.equal((await post(`/api/parties/${code}/join`, { userId: "" })).status, 422, "validation");
const joined = await post(`/api/parties/${code.toLowerCase()}/join`, joinReq("Guest"));
assert.equal(joined.status, 200, "codes are normalised");
const preview = await (await fetch(`${base}/api/parties/${code}/preview`)).json() as any;
assert.equal(preview.hostName, "Host");
assert.equal(preview.memberCount, 2);

const host = client(code, created.body.token);
await host.open;
assert.equal((await host.next("welcome")).you.displayName, "Host");
const guest = client(code, joined.body.token);
await guest.open;
await guest.next("welcome");
await host.next("members", (m) => m.members.every((x: any) => x.connected));

host.send({ type: "ping", clientMs: 42 });
assert.equal((await host.next("pong")).clientMs, 42);

guest.send({ type: "control", action: "setTrack", track: { videoId: "dQw4w9WgXcQ", title: "Song", artist: "Artist" }, positionMs: 0 });
const state = await host.next("state", (m) => m.playback.track?.videoId === "dQw4w9WgXcQ");
assert.equal(state.playback.isPlaying, true);
assert.equal((await host.next("activity")).detail, 'Changed the song to "Song"');

host.send({ type: "control", action: "setHostOnlyControl", enabled: true });
await guest.next("members", (m) => m.hostOnlyControl === true);
guest.send({ type: "control", action: "pause" });
assert.equal((await guest.next("error")).error, "host_only");

const guestId = joined.body.you.memberId;
host.send({ type: "control", action: "kick", memberId: guestId });
assert.equal((await guest.next("bye")).reason, "kicked");
await host.next("members", (m) => m.members.length === 1);

const snapshot = await fetch(`${base}/api/parties/${code}`, { headers: { authorization: `Bearer ${created.body.token}` } });
assert.equal(snapshot.status, 200);
assert.equal((await fetch(`${base}/api/parties/${code}`, { headers: { authorization: "Bearer nope" } })).status, 401);

const invite = await fetch(`${base}/invite/${code}`);
assert.equal(invite.status, 200);
assert.match(await invite.text(), new RegExp(`nuvx://party/${code}`));

assert.equal((await post(`/api/parties/${code}/leave`, {}, created.body.token)).status, 200);
assert.equal((await host.next("bye")).reason, "left");

const id = crypto.randomUUID();
assert.equal((await post("/api/presence", { id, platform: "android", open: true })).body.intervalSec, 300);
assert.equal((await post("/api/presence", { id, platform: "browser", open: true })).status, 403, "browser without Origin");
const stats = await (await fetch(`${base}/api/stats/live`)).json() as any;
assert.ok(stats.android >= 1);
assert.match(await (await fetch(`${base}/api/stats/live/badge.svg`)).text(), /LISTENING NOW/);

host.ws.close();
console.log("e2e ok", code);
