// Nuvx Sync: account-free spaces, QR device pairing and an ordered, idempotent operation log.
// No dependencies: node:http and node:sqlite. Binds to loopback; TLS is the reverse proxy's job.
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type Server } from "node:http";
import { DatabaseSync } from "node:sqlite";

export const API_VERSION = 1;
const MAX_BODY = 256 * 1024;
const MAX_OPS_PER_PUSH = 200;
const MAX_OP_BYTES = 32 * 1024;
const MAX_OPS_PER_SPACE = 200_000;
const MAX_BYTES_PER_SPACE = 20 * 1024 * 1024;
const MAX_DEVICES = 20;
// Whole-server cap: spaces are free to create, so without it one client could fill the VM's disk.
const MAX_DB_BYTES = 10 * 1024 ** 3;
const PULL_LIMIT = 500;
const PAIRING_TTL_MS = 2 * 60_000;
const RATE_PER_MIN = 120;
const PAIR_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ";

// Forward-only and additive, so the previous release still runs against a migrated database.
const MIGRATIONS = [
  `CREATE TABLE spaces (id TEXT PRIMARY KEY, created_ms INTEGER NOT NULL);
   CREATE TABLE devices (id TEXT PRIMARY KEY, space_id TEXT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
     name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_ms INTEGER NOT NULL, seen_ms INTEGER NOT NULL);
   CREATE TABLE ops (seq INTEGER PRIMARY KEY AUTOINCREMENT, space_id TEXT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
     op_id TEXT NOT NULL, device_id TEXT NOT NULL, kind TEXT NOT NULL, entity TEXT NOT NULL, payload TEXT NOT NULL,
     client_ms INTEGER NOT NULL, server_ms INTEGER NOT NULL, UNIQUE (space_id, op_id));
   CREATE INDEX ops_space_seq ON ops (space_id, seq);
   CREATE TABLE pairings (code TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, name TEXT NOT NULL,
     space_id TEXT, expires_ms INTEGER NOT NULL);`,
];

export function openDb(path: string): DatabaseSync {
  const db = new DatabaseSync(path);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
  const version = (db.prepare("PRAGMA user_version").get() as { user_version: number }).user_version;
  for (let i = version; i < MIGRATIONS.length; i++) {
    db.exec("BEGIN");
    try {
      db.exec(MIGRATIONS[i]);
      db.exec(`PRAGMA user_version = ${i + 1}`);
      db.exec("COMMIT");
    } catch (error) {
      db.exec("ROLLBACK");
      throw error;
    }
  }
  return db;
}

type Reply = { status: number; body: unknown };
type Device = { id: string; space_id: string };
const fail = (status: number, error: string, message: string): Reply => ({ status, body: { error, message } });
const hash = (value: string) => createHash("sha256").update(value).digest("hex");
const token = () => randomBytes(32).toString("base64url");
const id = () => randomBytes(12).toString("hex");
const str = (v: unknown, max: number) => (typeof v === "string" && v.trim() && v.trim().length <= max ? v.trim() : null);

function pairCode(): string {
  let out = "";
  while (out.length < 8) for (const b of randomBytes(16)) if (b < 231 && out.length < 8) out += PAIR_CODE_ALPHABET[b % 33];
  return out;
}

export function createApp(db: DatabaseSync, now: () => number = Date.now, maxDbBytes = MAX_DB_BYTES) {
  const buckets = new Map<string, { tokens: number; at: number }>();

  function allowed(ip: string): boolean {
    const t = now();
    if (buckets.size > 10_000) buckets.clear(); // bounded: a flood resets everyone rather than growing memory
    const b = buckets.get(ip) ?? { tokens: RATE_PER_MIN, at: t };
    b.tokens = Math.min(RATE_PER_MIN, b.tokens + ((t - b.at) / 60_000) * RATE_PER_MIN);
    b.at = t;
    buckets.set(ip, b);
    if (b.tokens < 1) return false;
    b.tokens -= 1;
    return true;
  }

  const pages = db.prepare("SELECT page_count * page_size AS n FROM pragma_page_count(), pragma_page_size()");
  const serverFull = () => (pages.get() as { n: number }).n >= maxDbBytes;
  const FULL = fail(507, "server_full", "This sync server is out of space. Try again later.");

  function auth(header: string | undefined): Device | null {
    const bearer = header?.match(/^bearer\s+(.+)$/i)?.[1].trim();
    if (!bearer) return null;
    const device = db.prepare("SELECT id, space_id FROM devices WHERE token_hash = ?").get(hash(bearer)) as Device | undefined;
    // At most one write a minute per device, so reads stay reads.
    if (device) db.prepare("UPDATE devices SET seen_ms = ? WHERE id = ? AND seen_ms < ?").run(now(), device.id, now() - 60_000);
    return device ?? null;
  }

  function addDevice(spaceId: string, name: string) {
    const deviceId = id(), secret = token();
    db.prepare("INSERT INTO devices (id, space_id, name, token_hash, created_ms, seen_ms) VALUES (?, ?, ?, ?, ?, ?)")
      .run(deviceId, spaceId, name, hash(secret), now(), now());
    return { spaceId, deviceId, token: secret };
  }

  function handle(method: string, path: string, query: URLSearchParams, authHeader: string | undefined, body: Record<string, unknown>): Reply {
    const route = `${method} ${path}`;
    if (route === "GET /healthz") return { status: 200, body: { ok: true, api: API_VERSION, schema: MIGRATIONS.length } };

    if (route === "POST /v1/spaces") {
      const name = str(body.deviceName, 80);
      if (!name) return fail(422, "invalid_name", "deviceName must be 1 to 80 characters.");
      if (serverFull()) return FULL;
      const spaceId = id();
      db.prepare("INSERT INTO spaces (id, created_ms) VALUES (?, ?)").run(spaceId, now());
      return { status: 201, body: addDevice(spaceId, name) };
    }

    // Pairing: the new device asks for a code and shows it as a QR. A trusted device approves it.
    // The code alone is useless without the secret, which never leaves the new device.
    if (route === "POST /v1/pairings") {
      const name = str(body.deviceName, 80);
      if (!name) return fail(422, "invalid_name", "deviceName must be 1 to 80 characters.");
      db.prepare("DELETE FROM pairings WHERE expires_ms < ?").run(now());
      const code = pairCode(), secret = token(), expiresAtMs = now() + PAIRING_TTL_MS;
      db.prepare("INSERT INTO pairings (code, secret_hash, name, expires_ms) VALUES (?, ?, ?, ?)").run(code, hash(secret), name, expiresAtMs);
      return { status: 201, body: { code, secret, expiresAtMs } };
    }
    const pairing = path.match(/^\/v1\/pairings\/([0-9A-Z]{8})\/(approve|claim)$/);
    if (method === "POST" && pairing) {
      const row = db.prepare("SELECT code, secret_hash, name, space_id FROM pairings WHERE code = ? AND expires_ms >= ?")
        .get(pairing[1], now()) as { code: string; secret_hash: string; name: string; space_id: string | null } | undefined;
      if (!row) return fail(404, "no_such_pairing", "That pairing code is unknown or has expired.");
      const full = (spaceId: string) =>
        (db.prepare("SELECT COUNT(*) AS n FROM devices WHERE space_id = ?").get(spaceId) as { n: number }).n >= MAX_DEVICES;
      const tooMany = () => fail(409, "too_many_devices", `A space holds at most ${MAX_DEVICES} devices.`);
      if (pairing[2] === "approve") {
        const device = auth(authHeader);
        if (!device) return fail(401, "unauthorized", "Missing or unknown device token.");
        if (full(device.space_id)) return tooMany();
        // First approval wins: anyone else who saw the QR must not move the device into their own space.
        if (row.space_id !== null && row.space_id !== device.space_id) return fail(409, "already_approved", "Another space already approved this code.");
        db.prepare("UPDATE pairings SET space_id = ? WHERE code = ? AND space_id IS NULL").run(device.space_id, row.code);
        return { status: 200, body: { ok: true, deviceName: row.name } };
      }
      const offered = Buffer.from(hash(str(body.secret, 200) ?? ""), "hex"), stored = Buffer.from(row.secret_hash, "hex");
      if (!timingSafeEqual(offered, stored)) return fail(401, "bad_secret", "Wrong pairing secret.");
      if (!row.space_id) return { status: 202, body: { pending: true } };
      if (full(row.space_id)) return tooMany(); // several codes approved at once must not pass the cap together
      db.prepare("DELETE FROM pairings WHERE code = ?").run(row.code); // single use
      return { status: 200, body: addDevice(row.space_id, row.name) };
    }

    const device = auth(authHeader);
    if (!device) return fail(401, "unauthorized", "Missing or unknown device token.");
    const space = device.space_id;

    if (route === "GET /v1/devices") {
      const devices = db.prepare("SELECT id, name, created_ms AS createdMs, seen_ms AS seenMs FROM devices WHERE space_id = ? ORDER BY created_ms").all(space);
      return { status: 200, body: { devices, you: device.id } };
    }
    const revoke = path.match(/^\/v1\/devices\/([0-9a-f]{24})$/);
    if (method === "DELETE" && revoke) {
      const done = db.prepare("DELETE FROM devices WHERE id = ? AND space_id = ?").run(revoke[1], space);
      return done.changes ? { status: 200, body: { ok: true } } : fail(404, "no_such_device", "That device is not in this space.");
    }

    if (route === "POST /v1/ops") {
      if (!Array.isArray(body.ops) || body.ops.length === 0 || body.ops.length > MAX_OPS_PER_PUSH) {
        return fail(422, "invalid_ops", `ops must hold 1 to ${MAX_OPS_PER_PUSH} operations.`);
      }
      const rows: { opId: string; kind: string; entity: string; payload: string; clientMs: number }[] = [];
      for (const raw of body.ops as Record<string, unknown>[]) {
        const opId = str(raw?.id, 64), kind = str(raw?.kind, 64), entity = str(raw?.entity, 200);
        const payload = JSON.stringify(raw?.payload ?? null);
        if (!opId || !kind || !entity || payload.length > MAX_OP_BYTES) return fail(422, "invalid_op", "Each op needs id, kind, entity and a payload under 32 KB.");
        rows.push({ opId, kind, entity, payload, clientMs: typeof raw.clientMs === "number" && Number.isFinite(raw.clientMs) ? Math.trunc(raw.clientMs) : now() });
      }
      if (serverFull()) return FULL;
      const stored = db.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(length(payload)), 0) AS bytes FROM ops WHERE space_id = ?").get(space) as { n: number; bytes: number };
      const adding = rows.reduce((sum, r) => sum + r.payload.length, 0);
      if (stored.n + rows.length > MAX_OPS_PER_SPACE || stored.bytes + adding > MAX_BYTES_PER_SPACE) {
        return fail(413, "space_full", "This space has reached its storage limit. Export and reset it.");
      }
      // Idempotent: a retried op keeps its first sequence number, so a replayed push changes nothing.
      const insert = db.prepare("INSERT OR IGNORE INTO ops (space_id, op_id, device_id, kind, entity, payload, client_ms, server_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
      const seqOf = db.prepare("SELECT seq FROM ops WHERE space_id = ? AND op_id = ?");
      db.exec("BEGIN");
      try {
        const seqs = rows.map((r) => {
          insert.run(space, r.opId, device.id, r.kind, r.entity, r.payload, r.clientMs, now());
          return (seqOf.get(space, r.opId) as { seq: number }).seq;
        });
        db.exec("COMMIT");
        return { status: 200, body: { seqs } };
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    }
    // Export pages like a pull (follow "more" with ?after=), so a full space never sits in memory at once.
    if (route === "GET /v1/ops" || route === "GET /v1/export") {
      const after = Math.max(0, Number(query.get("after")) || 0);
      const limit = PULL_LIMIT;
      const ops = (db.prepare("SELECT seq, op_id AS id, device_id AS deviceId, kind, entity, payload, client_ms AS clientMs, server_ms AS serverMs FROM ops WHERE space_id = ? AND seq > ? ORDER BY seq LIMIT ?")
        .all(space, after, limit) as { payload: string }[]).map((op) => ({ ...op, payload: JSON.parse(op.payload) }));
      return { status: 200, body: { api: API_VERSION, ops, more: ops.length === limit } };
    }
    if (route === "DELETE /v1/space") {
      db.prepare("DELETE FROM pairings WHERE space_id = ?").run(space);
      db.prepare("DELETE FROM spaces WHERE id = ?").run(space); // cascades to devices and ops
      return { status: 200, body: { ok: true } };
    }
    return fail(404, "not_found", "Not found.");
  }

  async function readBody(req: IncomingMessage): Promise<Record<string, unknown> | Reply> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > MAX_BODY) return fail(413, "request_too_large", "Request body is too large.");
      chunks.push(chunk);
    }
    if (size === 0) return {};
    try {
      const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : fail(422, "invalid_json", "Body must be one JSON object.");
    } catch {
      return fail(422, "invalid_json", "Malformed JSON body.");
    }
  }

  const server: Server = createServer({ requestTimeout: 15_000, headersTimeout: 10_000, maxHeaderSize: 8192 }, async (req, res) => {
    let reply: Reply;
    try {
      const url = new URL(req.url ?? "/", "http://local");
      // Loopback only sees the proxy, so the client address is the proxy's forwarded header.
      const ip = String(req.headers["x-forwarded-for"] ?? req.socket.remoteAddress ?? "").split(",").pop()!.trim();
      if (url.pathname !== "/healthz" && !allowed(ip)) reply = fail(429, "rate_limited", "Too many requests. Try again shortly.");
      else {
        const body = req.method === "POST" ? await readBody(req) : {};
        reply = "status" in body && "body" in body ? (body as Reply) : handle(req.method ?? "GET", url.pathname, url.searchParams, req.headers.authorization, body as Record<string, unknown>);
      }
    } catch (error) {
      console.error("request failed:", (error as Error).message); // message only: never log headers or bodies
      reply = fail(500, "internal", "Internal error.");
    }
    res.writeHead(reply.status, { "content-type": "application/json", "cache-control": "no-store" }).end(JSON.stringify(reply.body));
  });
  return { server, handle };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const db = openDb(process.env.NUVX_SYNC_DB ?? "nuvx-sync.db");
  const port = Number(process.env.NUVX_SYNC_PORT ?? 8787);
  const { server } = createApp(db);
  server.maxConnections = 200;
  server.listen(port, "127.0.0.1", () => console.log(`nuvx-sync listening on 127.0.0.1:${port}`));
  process.on("SIGTERM", () => server.close(() => { db.close(); process.exit(0); }));
}
