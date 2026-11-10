import { DurableObject } from "cloudflare:workers";
import * as P from "./party.ts";
import { handleCanvasUpload } from "./canvas-route.ts";
import { countReleaseDownloads } from "./downloads.ts";
import { invitePage } from "./invite.ts";

export interface Env {
  PARTIES: DurableObjectNamespace<Party>;
  PRESENCE: DurableObjectNamespace<Presence>;
  CREATE_LIMIT: RateLimit;
  PARTY_LIMIT: RateLimit;
  PRESENCE_LIMIT: RateLimit;
  CANVAS_LIMIT: RateLimit;
  CANVAS_PASSCODE?: string;
  CANVAS_GITHUB_TOKEN?: string;
}

export const ALLOWED_ORIGINS = new Set(["https://web.nuvx.lol", "https://nuvx.pages.dev", "http://localhost:5173"]);
const SOCKET_PROTOCOL = "nuvx.v1";
const SOCKET_TOKEN_PREFIX = "nuvx.token.";
const PRESENCE_INTERVAL_SEC = 300;
const PRESENCE_WINDOW_MS = (PRESENCE_INTERVAL_SEC + 90) * 1000;
const LISTENER_WINDOW_MS = 60_000;
const random = (n: number) => crypto.getRandomValues(new Uint8Array(n));

type Reply = { status: number; body: unknown };
const err = (f: P.Fail): Reply => ({ status: f.status, body: { error: f.error, message: f.message } });
const isFail = (v: unknown): v is P.Fail => !!v && typeof v === "object" && "error" in v && "status" in v;
const NO_PARTY = err(P.fail(404, "no_such_party", "No party with that code."));
const BAD_TOKEN = err(P.fail(401, "bad_token", "This device is not a member of that party."));

function json(body: unknown, status: number, origin: string | null, extra: Record<string, string> = {}) {
  const headers: Record<string, string> = { "content-type": "application/json", ...extra };
  if (origin && ALLOWED_ORIGINS.has(origin)) Object.assign(headers, { "access-control-allow-origin": origin, vary: "Origin" });
  return new Response(JSON.stringify(body), { status, headers });
}

async function readJson(req: Request): Promise<unknown | P.Fail> {
  const text = await req.text();
  if (new TextEncoder().encode(text).byteLength > P.MAX_BYTES) return P.fail(413, "request_too_large", "Request body is too large.");
  try { return JSON.parse(text); } catch { return P.fail(422, "invalid_json", "Malformed JSON body."); }
}

const bearer = (req: Request) => req.headers.get("authorization")?.match(/^bearer\s+(.+)$/i)?.[1].trim() ?? "";
const offeredProtocols = (req: Request) => (req.headers.get("sec-websocket-protocol") ?? "").split(",").map((s) => s.trim()).filter(Boolean);
const socketToken = (req: Request) => bearer(req) || (offeredProtocols(req).find((p) => p.startsWith(SOCKET_TOKEN_PREFIX))?.slice(SOCKET_TOKEN_PREFIX.length) ?? "");

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const origin = req.headers.get("origin");
    const ip = req.headers.get("cf-connecting-ip") ?? "local";
    const now = Date.now();
    const send = (r: Reply) => json(r.body, r.status, origin);
    if (req.method === "OPTIONS") {
      if (origin && !ALLOWED_ORIGINS.has(origin)) return new Response("origin not allowed", { status: 403 });
      return new Response(null, { status: 204, headers: origin ? { "access-control-allow-origin": origin, vary: "Origin", "access-control-allow-methods": "GET, POST, OPTIONS", "access-control-allow-headers": "Content-Type, Authorization" } : {} });
    }
    const route = `${req.method} ${url.pathname}`;
    if (route === "GET /") return send({ status: 200, body: { service: "nuvx-listen-together", website: "https://nuvx.lol", serverMs: now } });
    if (route === "GET /healthz") return send({ status: 200, body: { ok: true, serverMs: now } });
    if (route === "GET /api/time") return send({ status: 200, body: { serverMs: now } });
    if (route === "POST /canvas") return handleCanvasUpload(req, env);
    if (route === "POST /api/presence") return presence(req, env, ip, origin);
    if (route === "GET /api/stats/downloads") {
      try {
        const downloads = await env.PRESENCE.get(env.PRESENCE.idFromName("global")).releaseDownloads();
        return json({ downloads }, 200, origin, { "cache-control": "public, max-age=300" });
      } catch (error) {
        console.warn("Release download count unavailable", error);
        return send({ status: 502, body: { error: "downloads_unavailable", message: "GitHub download counts are temporarily unavailable." } });
      }
    }
    if (route === "GET /api/stats/live" || route === "GET /api/stats/live/badge.svg") {
      const c = await env.PRESENCE.get(env.PRESENCE.idFromName("global")).counts();
      if (!url.pathname.endsWith(".svg")) {
        return new Response(JSON.stringify(c), { headers: { "content-type": "application/json", "access-control-allow-origin": "*", "cache-control": "public, max-age=15" } });
      }
      return new Response(badge(c.online), { headers: { "content-type": "image/svg+xml;charset=utf-8", "access-control-allow-origin": "*", "cache-control": "no-cache, no-store, must-revalidate, max-age=0" } });
    }
    if (route === "POST /api/parties") {
      if (!(await env.CREATE_LIMIT.limit({ key: ip })).success) return send(err(P.fail(429, "create_rate_limited", "You can create up to two parties per minute. Please try again shortly.")));
      const body = await readJson(req);
      if (isFail(body)) return send(err(body));
      const join = P.validateJoin(body);
      if (isFail(join)) return send(err(join));
      for (let i = 0; i < 12; i++) {
        const code = P.newCode(random);
        const r = await env.PARTIES.get(env.PARTIES.idFromName(code)).create(code, join);
        if (r) return send(r);
      }
      return send(err(P.fail(503, "code_exhausted", "Could not allocate a party code.")));
    }

    const m = url.pathname.match(/^\/(?:api\/parties|ws\/parties|invite)\/([^/]+)(\/join|\/preview|\/leave)?$/);
    if (!m) return send({ status: 404, body: { error: "not_found", message: "Not found." } });
    if (!(await env.PARTY_LIMIT.limit({ key: ip })).success) return send(err(P.fail(429, "rate_limited", "Too many requests. Please try again shortly.")));
    const code = P.normaliseCode(decodeURIComponent(m[1]));
    const isInvite = url.pathname.startsWith("/invite/");
    if (!P.isValidCode(code)) {
      return isInvite ? new Response(invitePage({ code: m[1], active: false }, url.origin), { status: 400, headers: { "content-type": "text/html; charset=utf-8" } }) : send(NO_PARTY);
    }
    const stub = env.PARTIES.get(env.PARTIES.idFromName(code));
    if (isInvite) {
      const info = await stub.invite();
      return new Response(invitePage(info ?? { code, active: false }, url.origin), { status: info ? 200 : 404, headers: { "content-type": "text/html; charset=utf-8" } });
    }
    if (url.pathname.startsWith("/ws/")) {
      if (req.method !== "GET") return send({ status: 405, body: { error: "method_not_allowed", message: "Use GET." } });
      if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") return send({ status: 426, body: { error: "expected_websocket", message: "Expected a WebSocket upgrade." } });
      if (origin && !ALLOWED_ORIGINS.has(origin)) return new Response("origin not allowed", { status: 403 });
      if (!socketToken(req)) return new Response("missing token", { status: 401 });
      return stub.fetch(req);
    }
    const tail = m[2] ?? "";
    if (req.method === "POST" && tail === "/join") {
      const body = await readJson(req);
      if (isFail(body)) return send(err(body));
      const join = P.validateJoin(body);
      return send(isFail(join) ? err(join) : await stub.join(join));
    }
    if (req.method === "GET" && tail === "/preview") return send(await stub.preview());
    if ((req.method === "GET" && tail === "") || (req.method === "POST" && tail === "/leave")) {
      const token = bearer(req);
      if (!token) return send(err(P.fail(401, "unauthorized", "Missing bearer token.")));
      return send(tail ? await stub.leave(token) : await stub.snapshot(token));
    }
    return send({ status: 404, body: { error: "not_found", message: "Not found." } });
  },
};

async function presence(req: Request, env: Env, ip: string, origin: string | null): Promise<Response> {
  const send = (status: number, body: unknown) => json(body, status, origin);
  if (!(await env.PRESENCE_LIMIT.limit({ key: ip })).success) return send(429, { error: "rate_limited", message: "Too many presence pings from this address." });
  const body = await readJson(req);
  if (isFail(body)) return send(body.status, { error: body.error, message: body.message });
  const b = (body ?? {}) as Record<string, unknown>;
  if (typeof b.id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(b.id)) return send(422, { error: "invalid_id", message: "id must be a UUID." });
  if (b.platform !== "android" && b.platform !== "pc" && b.platform !== "browser") return send(422, { error: "invalid_platform", message: "platform must be android, pc or browser." });
  if (typeof b.open !== "boolean") return send(422, { error: "invalid_open", message: "open must be true or false." });
  if ((b.platform === "browser") !== (origin !== null) || (origin !== null && !ALLOWED_ORIGINS.has(origin))) {
    return send(403, { error: "origin_not_allowed", message: "Presence is only accepted from the apps and the web player." });
  }
  await env.PRESENCE.get(env.PRESENCE.idFromName("global")).ping(b.id.toLowerCase(), b.platform, b.open);
  return send(200, { intervalSec: PRESENCE_INTERVAL_SEC });
}

function badge(online: number): string {
  const value = String(online), valueWidth = 8.25 * value.length + 24, width = 124.25 + valueWidth;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="28" role="img" aria-label="LISTENING NOW: ${value}"><title>LISTENING NOW: ${value}</title><g shape-rendering="crispEdges"><rect width="124.25" height="28" fill="#0d1117"/><rect x="124.25" width="${valueWidth}" height="28" fill="#ffffff"/></g><g text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="100"><text fill="#fff" transform="scale(.1)" x="621.25" y="175" textLength="1002.5">LISTENING NOW</text><text fill="#000" transform="scale(.1)" x="${(124.25 + valueWidth / 2) * 10}" y="175" textLength="${82.5 * value.length}" font-weight="bold">${value}</text></g></svg>`;
}

type Attachment = { memberId: string };

export class Party extends DurableObject<Env> {
  private buckets = new Map<string, ReturnType<typeof P.freshBuckets>>();
  private lastStateAt = 0;
  private presence?: DurableObjectStub<Presence>;
  private publishedListeners?: { count: number; expiresAt: number };

  private sockets(memberId: string, except?: WebSocket) {
    return this.ctx.getWebSockets().filter((ws) => ws !== except && ws.readyState === WebSocket.OPEN && (ws.deserializeAttachment() as Attachment | null)?.memberId === memberId);
  }
  private connected = (except?: WebSocket) => (id: string) => this.sockets(id, except).length > 0;

  private async load(): Promise<P.Party | null> {
    const party = (await this.ctx.storage.get<P.Party>("party")) ?? null;
    if (party && P.isExpired(party, Date.now())) { await this.expire(); return null; }
    return party;
  }

  private async expire() {
    for (const ws of this.ctx.getWebSockets()) try { ws.close(1000, "party ended"); } catch { }
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
  }

  private async save(party: P.Party, except?: WebSocket) {
    const connected = this.connected(except);
    P.refreshEmptiness(party, connected, Date.now());
    await this.ctx.storage.put("party", party);
    await this.ctx.storage.setAlarm(P.nextExpiry(party, connected));
    this.publishListeners(party, connected);
  }

  private publishListeners(party: P.Party, connected = this.connected()) {
    const now = Date.now();
    const count = party.playback.isPlaying && party.playback.track
      ? party.members.filter((member) => connected(member.memberId)).length
      : 0;
    const previous = this.publishedListeners;
    if (previous?.count === count && (count === 0 || previous.expiresAt - now > LISTENER_WINDOW_MS / 2)) return;

    const expiresAt = Math.min(now + LISTENER_WINDOW_MS, party.createdAtMs + P.PARTY_MAX_AGE_MS);
    const presence = this.presence ??= this.env.PRESENCE.get(this.env.PRESENCE.idFromName("global"));
    this.publishedListeners = { count, expiresAt };
    this.ctx.waitUntil(presence.setPartyListeners(party.code, count, expiresAt).catch(() => {
      if (this.presence === presence) {
        this.presence = undefined;
        this.publishedListeners = undefined;
      }
    }));
  }

  private broadcast(frame: unknown, except?: WebSocket) {
    const msg = JSON.stringify(frame);
    for (const ws of this.ctx.getWebSockets()) if (ws !== except && ws.readyState === WebSocket.OPEN) try { ws.send(msg); } catch { }
    if ((frame as { type?: string }).type === "state") this.lastStateAt = Date.now();
  }

  private say(memberId: string, frame: unknown, close = false) {
    for (const ws of this.sockets(memberId)) {
      try { ws.send(JSON.stringify(frame)); if (close) ws.close(1000, "bye"); } catch { }
    }
  }

  private joined(party: P.Party, m: P.Member, now: number): Reply {
    return { status: 200, body: { code: party.code, token: m.token, you: P.memberWire(m, this.connected()(m.memberId)), party: P.partyWire(party, this.connected(), now) } };
  }

  async create(code: string, req: P.JoinRequest): Promise<Reply | null> {
    if (await this.load()) return null;
    const now = Date.now();
    const party = P.newParty(code, now, req.maxMembers ?? 5);
    if (req.autoplayEnabled !== undefined) party.playback.autoplayEnabled = req.autoplayEnabled;
    const m = P.join(party, req, random, now) as P.Member;
    await this.save(party);
    return { ...this.joined(party, m, now), status: 201 };
  }

  async join(req: P.JoinRequest): Promise<Reply> {
    const party = await this.load();
    if (!party) return NO_PARTY;
    const now = Date.now();
    const m = P.join(party, req, random, now);
    if (isFail(m)) return err(m);
    await this.save(party);
    return this.joined(party, m, now);
  }

  async snapshot(token: string): Promise<Reply> {
    const party = await this.load();
    if (!party) return NO_PARTY;
    if (!P.authenticate(party, token)) return BAD_TOKEN;
    return { status: 200, body: P.partyWire(party, this.connected(), Date.now()) };
  }

  async preview(): Promise<Reply> {
    const party = await this.load();
    return party ? { status: 200, body: P.previewWire(party) } : NO_PARTY;
  }

  async leave(token: string): Promise<Reply> {
    const party = await this.load();
    if (!party) return NO_PARTY;
    const m = P.authenticate(party, token);
    if (!m) return BAD_TOKEN;
    P.remove(party, m.memberId);
    this.say(m.memberId, { type: "bye", reason: "left" }, true);
    await this.save(party);
    this.broadcast(P.membersFrame(party, this.connected(), Date.now()));
    return { status: 200, body: { ok: true } };
  }

  async invite(): Promise<{ code: string; active: boolean; title?: string; artist?: string; memberCount?: number } | null> {
    const party = await this.load();
    if (!party) return null;
    return { code: party.code, active: true, title: party.playback.track?.title, artist: party.playback.track?.artist, memberCount: party.members.length };
  }

  async fetch(req: Request): Promise<Response> {
    const party = await this.load();
    if (!party) return new Response("no such party", { status: 404 });
    const member = P.authenticate(party, socketToken(req));
    if (!member) return new Response("bad token", { status: 401 });
    const older = this.sockets(member.memberId);
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ memberId: member.memberId } satisfies Attachment);
    for (const ws of older) try { ws.close(1000, "replaced"); } catch { }
    const now = Date.now();
    member.lastSeenMs = now;
    this.buckets.set(member.memberId, P.freshBuckets(now));
    await this.save(party);
    server.send(JSON.stringify({ type: "welcome", you: P.memberWire(member, true), party: P.partyWire(party, this.connected(), now), serverMs: now }));
    this.broadcast(P.membersFrame(party, this.connected(), now));
    const headers = offeredProtocols(req).includes(SOCKET_PROTOCOL) ? { "sec-websocket-protocol": SOCKET_PROTOCOL } : undefined;
    return new Response(null, { status: 101, webSocket: client, headers });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer) {
    const size = typeof data === "string" ? new TextEncoder().encode(data).byteLength : data.byteLength;
    if (size > P.MAX_BYTES || typeof data !== "string") return ws.close(1009, "message too large");
    let frame: Record<string, unknown>;
    try { frame = JSON.parse(data); } catch { return; }
    if (!frame || typeof frame !== "object" || typeof frame.type !== "string") return;
    const party = await this.load();
    const { memberId } = ws.deserializeAttachment() as Attachment;
    const member = party?.members.find((m) => m.memberId === memberId);
    if (!party || !member) {
      try { ws.send(JSON.stringify({ type: "bye", reason: "left" })); ws.close(1000, "not a member"); } catch { }
      return;
    }
    const now = Date.now();
    let b = this.buckets.get(memberId);
    if (!b) this.buckets.set(memberId, (b = P.freshBuckets(now)));
    const reply = (f: unknown) => ws.send(JSON.stringify(f));
    if (!P.spendFrame(b.frame, now)) return reply({ type: "error", error: "rate_limited", message: "Too many messages at once." });
    const heartbeat = () => {
      this.publishListeners(party);
      if (now - this.lastStateAt > P.HEARTBEAT_MS) this.broadcast(P.stateFrame(party, now));
    };
    switch (frame.type) {
      case "ping": reply({ type: "pong", clientMs: frame.clientMs ?? null, serverMs: now }); return heartbeat();
      case "report": return heartbeat();
      case "sync": return reply(P.stateFrame(party, now));
      case "syncQueue": return reply(P.queueFrame(party, now));
      case "control": {
        if (!P.spendControl(b.control, now)) return reply({ type: "error", error: "rate_limited", message: "Too many controls at once." });
        const queueBefore = party.playback.queueSeq;
        const out = P.applyControl(party, member, frame, now);
        if (!out.ok) return reply({ type: "error", error: out.error, message: out.message });
        if (out.kicked) this.say(out.kicked, { type: "bye", reason: "kicked", message: "The host removed you from this party." }, true);
        await this.save(party);
        if (party.playback.queueSeq !== queueBefore) this.broadcast(P.queueFrame(party, now));
        this.broadcast(P.stateFrame(party, now));
        if (frame.action === "kick" || frame.action === "setMaxMembers" || frame.action === "setHostOnlyControl") this.broadcast(P.membersFrame(party, this.connected(), now));
        this.broadcast(P.activityFrame(member, frame, now));
      }
    }
  }

  async webSocketClose(ws: WebSocket) { await this.dropped(ws); }
  async webSocketError(ws: WebSocket) { await this.dropped(ws); }

  private async dropped(ws: WebSocket) {
    const party = await this.load();
    if (!party) return;
    const { memberId } = (ws.deserializeAttachment() as Attachment | null) ?? { memberId: "" };
    const member = party.members.find((m) => m.memberId === memberId);
    if (member) member.lastSeenMs = Date.now();
    await this.save(party, ws);
    this.broadcast(P.membersFrame(party, this.connected(ws), Date.now()), ws);
  }

  async alarm() {
    const party = await this.load();
    if (!party) return;
    const now = Date.now();
    const gone = P.expiredMembers(party, this.connected(), now);
    for (const m of gone) P.remove(party, m.memberId);
    P.refreshEmptiness(party, this.connected(), now);
    if (P.isExpired(party, now)) return this.expire();
    await this.save(party);
    if (gone.length) this.broadcast(P.membersFrame(party, this.connected(), now));
  }
}

export class Presence extends DurableObject<Env> {
  private downloadRefresh?: Promise<number>;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS installs (id TEXT PRIMARY KEY, platform TEXT NOT NULL, last_ms INTEGER NOT NULL)");
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS playing_parties (code TEXT PRIMARY KEY, listeners INTEGER NOT NULL, expires_ms INTEGER NOT NULL)");
  }

  async ping(id: string, platform: string, open: boolean) {
    if (open) this.ctx.storage.sql.exec("INSERT INTO installs (id, platform, last_ms) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET platform = excluded.platform, last_ms = excluded.last_ms", id, platform, Date.now());
    else this.ctx.storage.sql.exec("DELETE FROM installs WHERE id = ?", id);
  }

  async setPartyListeners(code: string, count: number, expiresAt: number) {
    const sql = this.ctx.storage.sql;
    if (count === 0) sql.exec("DELETE FROM playing_parties WHERE code = ?", code);
    else sql.exec("INSERT INTO playing_parties (code, listeners, expires_ms) VALUES (?, ?, ?) ON CONFLICT(code) DO UPDATE SET listeners = excluded.listeners, expires_ms = excluded.expires_ms", code, count, expiresAt);
  }

  async releaseDownloads() {
    const cached = await this.ctx.storage.get<{ downloads: number; refreshAt: number }>("release-downloads");
    if (cached && cached.refreshAt > Date.now()) return cached.downloads;

    this.downloadRefresh ??= this.refreshDownloads(cached?.downloads).finally(() => { this.downloadRefresh = undefined; });
    return this.downloadRefresh;
  }

  private async refreshDownloads(downloads?: number) {
    let refreshIn = 300_000;
    try {
      downloads = await countReleaseDownloads(fetch, this.env.CANVAS_GITHUB_TOKEN);
    } catch (error) {
      if (downloads === undefined) throw error;
      console.warn("Serving cached release download count", error);
      refreshIn = 60_000;
    }
    await this.ctx.storage.put("release-downloads", { downloads, refreshAt: Date.now() + refreshIn });
    return downloads;
  }

  async counts() {
    const now = Date.now();
    this.ctx.storage.sql.exec("DELETE FROM installs WHERE last_ms < ?", now - PRESENCE_WINDOW_MS);
    this.ctx.storage.sql.exec("DELETE FROM playing_parties WHERE expires_ms <= ?", now);
    const { listening } = this.ctx.storage.sql.exec<{ listening: number }>("SELECT COALESCE(SUM(listeners), 0) AS listening FROM playing_parties").one();
    const c = { online: 0, android: 0, pc: 0, browser: 0, updatedAt: now, listening };
    for (const row of this.ctx.storage.sql.exec<{ platform: "android" | "pc" | "browser"; n: number }>("SELECT platform, COUNT(*) AS n FROM installs GROUP BY platform")) {
      c[row.platform] = row.n;
      c.online += row.n;
    }
    return c;
  }
}
