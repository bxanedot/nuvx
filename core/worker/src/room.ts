// Listen Together room logic, kept free of Cloudflare APIs so it runs under `node --test`.
// Rooms sync track IDs and playback state only; every client streams audio from its own source.

export type Member = { id: string; name: string };
/** [title]/[artists] are display text only; clients derive artwork from [trackId], so the server never relays URLs. */
export type Playback = { trackId: string | null; positionMs: number; playing: boolean; updatedAt: number; queue: string[]; title?: string; artists?: string[] };
export type RoomState = { host: string | null; members: Member[]; playback: Playback };

export type ClientMsg =
  | { t: "ping"; c: number }
  | { t: "state"; trackId: string | null; positionMs: number; playing: boolean; queue?: string[]; title?: string; artists?: string[] }
  | { t: "handoff"; to: string };

export type ServerMsg =
  | { t: "welcome"; you: string; s: number; room: RoomState }
  | { t: "members"; host: string | null; members: Member[] }
  | { t: "state"; s: number; playback: Playback }
  | { t: "pong"; c: number; s: number }
  | { t: "error"; code: "bad_message" | "not_host" | "rate_limited" | "room_full" | "unknown_member" };

export type Outcome = { reply?: ServerMsg; broadcast?: ServerMsg };

export const MAX_MEMBERS = 50;
export const MAX_QUEUE = 200;
const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

export function emptyRoom(now: number): RoomState {
  return { host: null, members: [], playback: { trackId: null, positionMs: 0, playing: false, updatedAt: now, queue: [] } };
}

export function cleanName(raw: unknown): string {
  const name = typeof raw === "string" ? raw.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 32) : "";
  return name || "Listener";
}

export function join(room: RoomState, member: Member, now: number): Outcome {
  if (room.members.length >= MAX_MEMBERS) return { reply: { t: "error", code: "room_full" } };
  room.members.push(member);
  room.host ??= member.id;
  return { reply: { t: "welcome", you: member.id, s: now, room }, broadcast: members(room) };
}

/** Removes a member; the longest-present remaining member becomes host. */
export function leave(room: RoomState, id: string): Outcome {
  room.members = room.members.filter((m) => m.id !== id);
  if (room.host === id) room.host = room.members[0]?.id ?? null;
  return { broadcast: members(room) };
}

export function handle(room: RoomState, from: string, raw: unknown, now: number): Outcome {
  const msg = parse(raw);
  if (!msg) return { reply: { t: "error", code: "bad_message" } };
  if (msg.t === "ping") return { reply: { t: "pong", c: msg.c, s: now } };
  if (room.host !== from) return { reply: { t: "error", code: "not_host" } };
  if (msg.t === "handoff") {
    if (!room.members.some((m) => m.id === msg.to)) return { reply: { t: "error", code: "unknown_member" } };
    room.host = msg.to;
    return { broadcast: members(room) };
  }
  room.playback = {
    trackId: msg.trackId,
    positionMs: msg.positionMs,
    playing: msg.playing,
    updatedAt: now,
    queue: msg.queue ?? room.playback.queue,
    title: msg.title,
    artists: msg.artists,
  };
  return { broadcast: { t: "state", s: now, playback: room.playback } };
}

/** Where playback should be at server time [now]; clients add their measured clock offset. */
export function expectedPosition(p: Playback, now: number): number {
  return p.playing ? p.positionMs + Math.max(0, now - p.updatedAt) : p.positionMs;
}

function members(room: RoomState): ServerMsg {
  return { t: "members", host: room.host, members: room.members };
}

function parse(raw: unknown): ClientMsg | null {
  let m: any;
  try {
    m = typeof raw === "string" ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
  if (!m || typeof m !== "object") return null;
  const num = (v: unknown) => typeof v === "number" && Number.isFinite(v) && v >= 0;
  switch (m.t) {
    case "ping":
      return num(m.c) ? { t: "ping", c: m.c } : null;
    case "handoff":
      return typeof m.to === "string" ? { t: "handoff", to: m.to } : null;
    case "state": {
      const trackOk = m.trackId === null || (typeof m.trackId === "string" && VIDEO_ID.test(m.trackId));
      const queueOk = m.queue === undefined ||
        (Array.isArray(m.queue) && m.queue.length <= MAX_QUEUE && m.queue.every((q: unknown) => typeof q === "string" && VIDEO_ID.test(q)));
      const text = (v: unknown, max: number) => typeof v === "string" && v.length <= max;
      const titleOk = m.title === undefined || text(m.title, 200);
      const artistsOk = m.artists === undefined || (Array.isArray(m.artists) && m.artists.length <= 10 && m.artists.every((a: unknown) => text(a, 100)));
      if (!trackOk || !queueOk || !titleOk || !artistsOk || !num(m.positionMs) || typeof m.playing !== "boolean") return null;
      return { t: "state", trackId: m.trackId, positionMs: m.positionMs, playing: m.playing, queue: m.queue, title: m.title, artists: m.artists };
    }
    default:
      return null;
  }
}

/** Token bucket: [capacity] messages, refilled at [perSecond]. */
export type Bucket = { tokens: number; at: number };
export function take(b: Bucket, now: number, capacity = 20, perSecond = 2): boolean {
  b.tokens = Math.min(capacity, b.tokens + ((now - b.at) / 1000) * perSecond);
  b.at = now;
  if (b.tokens < 1) return false;
  b.tokens -= 1;
  return true;
}

const ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"; // no 0/O/1/I/L/U look-alikes
export function roomCode(random: (n: number) => Uint8Array): string {
  return Array.from(random(6), (b) => ALPHABET[b % ALPHABET.length]).join("");
}
export const ROOM_CODE = /^[ABCDEFGHJKMNPQRSTVWXYZ2-9]{6}$/;
