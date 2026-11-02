import { DurableObject } from "cloudflare:workers";
import { ROOM_CODE, cleanName, emptyRoom, handle, join, leave, roomCode, take, type Bucket, type Outcome, type RoomState } from "./room.ts";

export interface Env {
  ROOMS: DurableObjectNamespace<Room>;
  CREATE_LIMIT: RateLimit;
}

const IDLE_EXPIRY_MS = 30 * 60 * 1000;
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "access-control-allow-origin": "*" } });

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    // Visiting the bare address in a browser should say what this is, not look broken.
    if (req.method === "GET" && url.pathname === "/") {
      return json({ ok: true, service: "nuvx Listen Together", about: "This server syncs Listen Together rooms for the nuvx app. Open nuvx and use the Together tab.", website: "https://nuvx.lol" });
    }
    if (req.method === "POST" && url.pathname === "/rooms") {
      const ip = req.headers.get("cf-connecting-ip") ?? "local";
      if (!(await env.CREATE_LIMIT.limit({ key: ip })).success) return json({ error: "rate limited" }, 429);
      const code = roomCode((n) => crypto.getRandomValues(new Uint8Array(n)));
      await env.ROOMS.get(env.ROOMS.idFromName(code)).create();
      return json({ code });
    }
    const m = url.pathname.match(/^\/rooms\/([^/]+)\/ws$/);
    if (m && ROOM_CODE.test(m[1])) {
      if (req.headers.get("upgrade") !== "websocket") return json({ error: "expected websocket" }, 426);
      return env.ROOMS.get(env.ROOMS.idFromName(m[1])).fetch(req);
    }
    return json({ error: "not found" }, 404);
  },
};

type Attachment = { id: string; bucket: Bucket };

/** One Durable Object per room. Uses WebSocket hibernation, so idle rooms cost nothing. */
export class Room extends DurableObject<Env> {
  async create() {
    await this.ctx.storage.put("room", emptyRoom(Date.now()));
    await this.ctx.storage.setAlarm(Date.now() + IDLE_EXPIRY_MS);
  }

  async fetch(req: Request): Promise<Response> {
    const room = await this.ctx.storage.get<RoomState>("room");
    if (!room) return json({ error: "room not found" }, 404);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    const id = crypto.randomUUID();
    const name = cleanName(new URL(req.url).searchParams.get("name"));
    server.serializeAttachment({ id, bucket: { tokens: 20, at: Date.now() } } satisfies Attachment);
    const out = join(room, { id, name }, Date.now());
    if (out.reply?.t === "error") {
      server.send(JSON.stringify(out.reply));
      server.close(1008, "room full");
    } else {
      await this.save(room, out, server);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer) {
    const att = ws.deserializeAttachment() as Attachment;
    const now = Date.now();
    const allowed = take(att.bucket, now);
    ws.serializeAttachment(att);
    if (!allowed) return ws.send(JSON.stringify({ t: "error", code: "rate_limited" }));
    const room = (await this.ctx.storage.get<RoomState>("room"))!;
    await this.save(room, handle(room, att.id, typeof data === "string" ? data : null, now), ws);
  }

  async webSocketClose(ws: WebSocket) {
    await this.drop(ws);
  }

  async webSocketError(ws: WebSocket) {
    await this.drop(ws);
  }

  async alarm() {
    // Expire only when nobody is connected; otherwise check again later.
    if (this.ctx.getWebSockets().length === 0) await this.ctx.storage.deleteAll();
    else await this.ctx.storage.setAlarm(Date.now() + IDLE_EXPIRY_MS);
  }

  private async drop(ws: WebSocket) {
    const room = await this.ctx.storage.get<RoomState>("room");
    if (!room) return;
    const { id } = ws.deserializeAttachment() as Attachment;
    await this.save(room, leave(room, id), ws, true);
    if (room.members.length === 0) await this.ctx.storage.setAlarm(Date.now() + IDLE_EXPIRY_MS);
  }

  private async save(room: RoomState, out: Outcome, from: WebSocket, closing = false) {
    await this.ctx.storage.put("room", room);
    if (out.reply) from.send(JSON.stringify(out.reply));
    if (out.broadcast) {
      const msg = JSON.stringify(out.broadcast);
      for (const ws of this.ctx.getWebSockets()) if (!(closing && ws === from)) try { ws.send(msg); } catch { /* peer already gone */ }
    }
  }
}
