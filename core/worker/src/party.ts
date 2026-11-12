export const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 6;
export const PLAY_LEAD_MS = 350;
export const MAX_UPCOMING = 25;
export const MAX_QUEUE = 1 + MAX_UPCOMING;
export const DISCONNECT_GRACE_MS = 45_000;
export const EMPTY_PARTY_TTL_MS = 120_000;
export const PARTY_MAX_AGE_MS = 12 * 60 * 60 * 1000;
export const HEARTBEAT_MS = 5_000;
export const MAX_BYTES = 16 * 1024;
const CONTROL_RATE = 25;
const FRAME_RATE = 30;
const AVATAR_HOSTS = new Set(["cdn.discordapp.com", "i.scdn.co", "lh3.googleusercontent.com", "yt3.ggpht.com", "yt3.googleusercontent.com"]);

export type Track = { videoId: string; title: string; artist: string; thumbnailUrl?: string; durationMs?: number; fromAutoplay: boolean };
export type Playback = {
  track: Track | null; queue: Track[]; queueIndex: number; isPlaying: boolean; positionMs: number; anchorMs: number;
  seq: number; queueSeq: number; updatedBy: string | null; updatedAtMs: number; startedBy: string | null;
  startedByName: string | null; autoplayEnabled: boolean;
};
export type Member = {
  memberId: string; userId: string; deviceId: string; displayName: string; avatarUrl?: string; token: string;
  isHost: boolean; joinedAtMs: number; lastSeenMs: number;
};
export type Party = {
  code: string; members: Member[]; playback: Playback; maxMembers: number; hostOnlyControl: boolean;
  createdAtMs: number; emptySinceMs: number | null;
};
export type JoinRequest = { userId: string; deviceId: string; displayName: string; avatarUrl?: string; maxMembers?: number; autoplayEnabled?: boolean };
export type Bucket = { tokens: number; at: number };
export type Fail = { status: number; error: string; message: string };
type Frame = Record<string, unknown>;

export const fail = (status: number, error: string, message: string): Fail => ({ status, error, message });

export function newCode(random: (n: number) => Uint8Array): string {
  let out = "";
  while (out.length < CODE_LENGTH) for (const b of random(16)) if (b < 231 && out.length < CODE_LENGTH) out += CODE_ALPHABET[b % 33];
  return out;
}

export function normaliseCode(code: string): string {
  return [...code.trim().toUpperCase()].filter((c) => /[\p{L}\p{N}]/u.test(c))
    .map((c) => (c === "I" || c === "L" ? "1" : c === "O" ? "0" : c)).join("");
}

export const isValidCode = (code: string) => code.length === CODE_LENGTH && [...code].every((c) => CODE_ALPHABET.includes(c));

export const randomHex = (random: (n: number) => Uint8Array, n: number) => [...random(n)].map((b) => b.toString(16).padStart(2, "0")).join("");

export function validateJoin(raw: unknown): JoinRequest | Fail {
  const bad = (message: string) => fail(422, "validation_error", message);
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return fail(422, "invalid_json", "Request body must contain one JSON object.");
  const r = raw as Frame;
  const str = (v: unknown) => (typeof v === "string" ? v.trim() : "");
  const userId = str(r.userId), deviceId = str(r.deviceId), displayName = str(r.displayName);
  if (!userId || userId.length > 128) return bad("userId must be between 1 and 128 characters");
  if (!deviceId || deviceId.length > 128) return bad("deviceId must be between 1 and 128 characters");
  if (!displayName || displayName.length > 80) return bad("displayName must be between 1 and 80 characters");
  const req: JoinRequest = { userId, deviceId, displayName };
  if (r.avatarUrl != null) {
    const avatar = str(r.avatarUrl);
    if (avatar.length > 1000) return bad("avatarUrl must not exceed 1000 characters");
    if (avatar) {
      let url: URL;
      try { url = new URL(avatar); } catch { return bad("avatarUrl must be a trusted HTTPS image URL"); }
      if (url.protocol !== "https:" || !AVATAR_HOSTS.has(url.hostname) || (url.port && url.port !== "443") || url.username || url.password) return bad("avatarUrl must be a trusted HTTPS image URL");
      req.avatarUrl = url.href;
    }
  }
  if (r.maxMembers != null) {
    if (typeof r.maxMembers !== "number" || !Number.isInteger(r.maxMembers) || r.maxMembers < 2 || r.maxMembers > 10) return bad("maxMembers must be between 2 and 10");
    req.maxMembers = r.maxMembers;
  }
  if (typeof r.autoplayEnabled === "boolean") req.autoplayEnabled = r.autoplayEnabled;
  return req;
}

export function trackFromWire(raw: unknown): Track | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Frame;
  const videoId = typeof r.videoId === "string" ? r.videoId.slice(0, 128) : "";
  if (!videoId) return null;
  const track: Track = {
    videoId,
    title: typeof r.title === "string" ? r.title.slice(0, 300) : "",
    artist: typeof r.artist === "string" ? r.artist.slice(0, 300) : "",
    fromAutoplay: r.fromAutoplay === true,
  };
  if (typeof r.thumbnailUrl === "string" && r.thumbnailUrl) track.thumbnailUrl = r.thumbnailUrl.slice(0, 1000);
  if (typeof r.durationMs === "number" && Number.isFinite(r.durationMs) && r.durationMs > 0) track.durationMs = Math.trunc(r.durationMs);
  return track;
}

export function newParty(code: string, now: number, maxMembers = 5): Party {
  return {
    code, members: [], maxMembers: maxMembers >= 2 && maxMembers <= 10 ? maxMembers : 5, hostOnlyControl: false, createdAtMs: now, emptySinceMs: now,
    playback: { track: null, queue: [], queueIndex: -1, isPlaying: false, positionMs: 0, anchorMs: now, seq: 0, queueSeq: 0, updatedBy: null, updatedAtMs: now, startedBy: null, startedByName: null, autoplayEnabled: false },
  };
}

export function positionAt(p: Playback, now: number): number {
  const dur = p.track?.durationMs;
  const pos = p.isPlaying ? p.positionMs + Math.max(0, now - p.anchorMs) : p.positionMs;
  return Math.min(Math.max(0, pos), dur && dur > 0 ? dur : Number.POSITIVE_INFINITY);
}

const touch = (p: Playback, by: string, now: number) => { p.seq++; p.updatedBy = by; p.updatedAtMs = now; };
const touchQueue = (p: Playback, by: string, now: number) => { p.queueSeq++; p.updatedBy = by; p.updatedAtMs = now; };

export function nearestIndexOf(queue: Track[], videoId: string, around: number): number {
  if (queue[around + 1]?.videoId === videoId && around + 1 >= 0) return around + 1;
  if (around - 1 >= 0 && queue[around - 1]?.videoId === videoId) return around - 1;
  let best = -1, bestDistance = 0;
  queue.forEach((t, i) => {
    if (t.videoId !== videoId) return;
    let d = i - around;
    if (d < 0) d = -d * 2 + 1;
    if (best === -1 || d < bestDistance) { best = i; bestDistance = d; }
  });
  return best;
}

function setTrack(p: Playback, by: string, track: Track | null, positionMs: number, isPlaying: boolean, queueIndex: number | null, name: string, now: number) {
  p.track = track;
  p.positionMs = Math.min(Math.max(0, positionMs), track?.durationMs && track.durationMs > 0 ? track.durationMs : Number.POSITIVE_INFINITY);
  p.isPlaying = isPlaying && track !== null;
  p.anchorMs = now + (p.isPlaying ? PLAY_LEAD_MS : 0);
  if (queueIndex !== null && queueIndex >= 0 && queueIndex < p.queue.length && track && p.queue[queueIndex].videoId === track.videoId) p.queueIndex = queueIndex;
  else if (track) p.queueIndex = nearestIndexOf(p.queue, track.videoId, p.queueIndex);
  else if (queueIndex !== null) p.queueIndex = queueIndex;
  p.startedBy = by;
  p.startedByName = name;
  touch(p, by, now);
}

function setQueue(p: Playback, by: string, queue: Track[], queueIndex: number, now: number) {
  if (p.track && (queueIndex < 0 || queueIndex >= queue.length || queue[queueIndex].videoId !== p.track.videoId)) {
    queueIndex = nearestIndexOf(queue, p.track.videoId, queueIndex);
  }
  if (queueIndex >= 0 && queueIndex < queue.length) {
    p.queue = queue.slice(0, queueIndex + 1 + MAX_UPCOMING);
    p.queueIndex = queueIndex;
  } else {
    p.queue = queue.slice(0, MAX_QUEUE);
    p.queueIndex = queueIndex >= 0 && queueIndex < p.queue.length ? queueIndex : -1;
  }
  touchQueue(p, by, now);
}

export function withoutQueuedAutoplay(queue: Track[], queueIndex: number, tracks: Track[]): Track[] {
  const waiting = new Set(queue.slice(Math.max(0, queueIndex + 1)).map((t) => t.videoId));
  return tracks.filter((t) => {
    if (!t.fromAutoplay) return true;
    if (waiting.has(t.videoId)) return false;
    waiting.add(t.videoId);
    return true;
  });
}

function addUpcoming(p: Playback, by: string, tracks: Track[], playNext: boolean, now: number): string | null {
  const upcoming = p.queueIndex >= 0 ? Math.max(0, p.queue.length - 1 - p.queueIndex) : p.queue.length;
  const slots = MAX_UPCOMING - upcoming;
  if (slots <= 0) return "queue_full";
  let add = withoutQueuedAutoplay(p.queue, p.queueIndex, tracks);
  if (add.length === 0 && tracks.length > 0) return null;
  add = add.slice(0, slots);
  if (add.length === 0) return "no_tracks";
  if (playNext && p.queueIndex >= 0 && p.queueIndex < p.queue.length) p.queue.splice(p.queueIndex + 1, 0, ...add);
  else p.queue.push(...add);
  touchQueue(p, by, now);
  return null;
}

export function join(party: Party, req: JoinRequest, random: (n: number) => Uint8Array, now: number): Member | Fail {
  const known = party.members.find((m) => m.deviceId === req.deviceId);
  if (known) {
    Object.assign(known, { displayName: req.displayName, avatarUrl: req.avatarUrl, userId: req.userId, lastSeenMs: now, token: randomHex(random, 24) });
    return known;
  }
  if (party.members.length >= party.maxMembers) return fail(409, "party_full", `This party is full (${party.maxMembers} devices).`);
  const m: Member = {
    memberId: randomHex(random, 8), userId: req.userId, deviceId: req.deviceId, displayName: req.displayName, avatarUrl: req.avatarUrl,
    token: randomHex(random, 24), isHost: party.members.length === 0, joinedAtMs: now, lastSeenMs: now,
  };
  party.members.push(m);
  return m;
}

export function authenticate(party: Party, token: string): Member | null {
  let found: Member | null = null;
  for (const m of party.members) {
    let diff = m.token.length ^ token.length;
    for (let i = 0; i < m.token.length; i++) diff |= m.token.charCodeAt(i) ^ (token.charCodeAt(i) || 0);
    if (diff === 0) found = m;
  }
  return found;
}

export function remove(party: Party, memberId: string): Member | null {
  const i = party.members.findIndex((m) => m.memberId === memberId);
  if (i < 0) return null;
  const [m] = party.members.splice(i, 1);
  if (m.isHost && party.members.length > 0) party.members.reduce((a, b) => (b.joinedAtMs < a.joinedAtMs ? b : a)).isHost = true;
  return m;
}

export function refreshEmptiness(party: Party, connected: (id: string) => boolean, now: number) {
  if (party.members.some((m) => connected(m.memberId))) party.emptySinceMs = null;
  else party.emptySinceMs ??= now;
}

export function spend(bucket: Bucket, rate: number, now: number): boolean {
  bucket.tokens = Math.min(rate, bucket.tokens + Math.max(0, now - bucket.at) / 1000 * rate);
  bucket.at = now;
  if (bucket.tokens < 1) return false;
  bucket.tokens -= 1;
  return true;
}
export const spendFrame = (b: Bucket, now: number) => spend(b, FRAME_RATE, now);
export const spendControl = (b: Bucket, now: number) => spend(b, CONTROL_RATE, now);
export const freshBuckets = (now: number) => ({ frame: { tokens: FRAME_RATE, at: now }, control: { tokens: CONTROL_RATE, at: now } });

export const isExpired = (party: Party, now: number) =>
  now - party.createdAtMs > PARTY_MAX_AGE_MS || (party.emptySinceMs !== null && now - party.emptySinceMs > EMPTY_PARTY_TTL_MS);

export const expiredMembers = (party: Party, connected: (id: string) => boolean, now: number) =>
  party.members.filter((m) => !connected(m.memberId) && now - m.lastSeenMs > DISCONNECT_GRACE_MS);

export function nextExpiry(party: Party, connected: (id: string) => boolean): number {
  const times = [party.createdAtMs + PARTY_MAX_AGE_MS];
  if (party.emptySinceMs !== null) times.push(party.emptySinceMs + EMPTY_PARTY_TTL_MS);
  for (const m of party.members) if (!connected(m.memberId)) times.push(m.lastSeenMs + DISCONNECT_GRACE_MS);
  return Math.min(...times) + 1;
}

const sortedMembers = (party: Party) => [...party.members].sort((a, b) => a.joinedAtMs - b.joinedAtMs);

export const memberWire = (m: Member, connected: boolean) => ({
  memberId: m.memberId, userId: m.userId, displayName: m.displayName, avatarUrl: m.avatarUrl ?? null, isHost: m.isHost,
  connected, joinedAtMs: m.joinedAtMs, lastSeenMs: m.lastSeenMs,
});

export function playbackWire(p: Playback, now: number) {
  return {
    seq: p.seq, track: p.track, queueSeq: p.queueSeq, queueIndex: p.queueIndex, queueLength: p.queue.length, isPlaying: p.isPlaying,
    positionMs: p.positionMs, anchorMs: p.anchorMs, effectivePositionMs: positionAt(p, now), updatedBy: p.updatedBy,
    startedBy: p.startedBy, startedByName: p.startedByName, autoplayEnabled: p.autoplayEnabled, updatedAtMs: p.updatedAtMs,
  };
}

export const queueWire = (p: Playback) => ({ seq: p.queueSeq, index: p.queueIndex, items: p.queue });

export const partyWire = (party: Party, connected: (id: string) => boolean, now: number) => ({
  code: party.code, createdAtMs: party.createdAtMs, maxMembers: party.maxMembers, hostOnlyControl: party.hostOnlyControl,
  members: sortedMembers(party).map((m) => memberWire(m, connected(m.memberId))),
  playback: playbackWire(party.playback, now), queue: queueWire(party.playback), serverMs: now,
});

export const stateFrame = (party: Party, now: number) => ({ type: "state", playback: playbackWire(party.playback, now), serverMs: now });
export const queueFrame = (party: Party, now: number) => ({ type: "queue", queue: queueWire(party.playback), serverMs: now });
export const membersFrame = (party: Party, connected: (id: string) => boolean, now: number) => ({
  type: "members", members: sortedMembers(party).map((m) => memberWire(m, connected(m.memberId))), maxMembers: party.maxMembers,
  hostOnlyControl: party.hostOnlyControl, serverMs: now,
});

export function previewWire(party: Party) {
  const members = sortedMembers(party);
  return {
    code: party.code, hostName: members.find((m) => m.isHost)?.displayName ?? "", memberCount: members.length, maxMembers: party.maxMembers,
    isFull: members.length >= party.maxMembers, members: members.map((m) => ({ displayName: m.displayName, avatarUrl: m.avatarUrl ?? null, isHost: m.isHost })),
  };
}

const CONTROL_ACTIONS = new Set(["play", "pause", "seek", "setTrack", "setQueue", "queueAdd", "queueRemove", "queueClear", "queueMove", "next", "previous", "setAutoplay"]);

export type ControlResult = { ok: true; kicked?: string } | { ok: false; error: string; message: string };

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? Math.trunc(v) : null);
const tracksOf = (v: unknown) => (Array.isArray(v) ? v.map(trackFromWire).filter((t): t is Track => t !== null) : []);

export function applyControl(party: Party, member: Member, frame: Frame, now: number): ControlResult {
  const action = typeof frame.action === "string" ? frame.action : "";
  const no = (error: string, message: string): ControlResult => ({ ok: false, error, message });
  if (CONTROL_ACTIONS.has(action) && party.hostOnlyControl && !member.isHost) return no("host_only", "Only the host can control the music in this party.");
  const p = party.playback, by = member.memberId, pos = num(frame.positionMs);
  switch (action) {
    case "play": {
      p.positionMs = Math.min(Math.max(0, pos ?? positionAt(p, now)), p.track?.durationMs && p.track.durationMs > 0 ? p.track.durationMs : Number.POSITIVE_INFINITY); p.isPlaying = true; p.anchorMs = now + PLAY_LEAD_MS; touch(p, by, now); break;
    }
    case "pause": {
      p.positionMs = Math.min(Math.max(0, pos ?? positionAt(p, now)), p.track?.durationMs && p.track.durationMs > 0 ? p.track.durationMs : Number.POSITIVE_INFINITY); p.isPlaying = false; p.anchorMs = now; touch(p, by, now); break;
    }
    case "seek":
      if (pos === null) return no("missing_position", "seek requires positionMs");
      p.positionMs = Math.min(Math.max(0, pos), p.track?.durationMs && p.track.durationMs > 0 ? p.track.durationMs : Number.POSITIVE_INFINITY); p.anchorMs = now + (p.isPlaying ? PLAY_LEAD_MS : 0); touch(p, by, now); break;
    case "setTrack": {
      const t = trackFromWire(frame.track);
      if (!t) return no("invalid_track", "Track must include videoId");
      setTrack(p, by, t, pos ?? 0, frame.isPlaying !== false, num(frame.queueIndex), member.displayName, now); break;
    }
    case "setQueue": setQueue(p, by, tracksOf(frame.queue), num(frame.queueIndex) ?? -1, now); break;
    case "queueAdd": {
      const single = trackFromWire(frame.track);
      const reason = addUpcoming(p, by, single ? [single] : tracksOf(frame.tracks), frame.playNext === true, now);
      if (reason === "queue_full") return no("queue_full", `Queue is full (maximum ${MAX_UPCOMING} upcoming songs).`);
      if (reason) return no(reason, "Could not add track to queue.");
      break;
    }
    case "queueRemove": {
      let vid = typeof frame.videoId === "string" ? frame.videoId : "";
      const idx = num(frame.index);
      if (!vid && idx !== null && idx >= 0 && idx < p.queue.length) vid = p.queue[idx].videoId;
      if (!vid) return no("missing_track", "queueRemove requires videoId or index");
      const match = p.queue.findIndex((t) => t.videoId === vid);
      if (match < 0 || match === p.queueIndex) return no("not_found", "Track is not in the upcoming queue");
      p.queue.splice(match, 1);
      if (p.queueIndex > match) p.queueIndex--;
      touchQueue(p, by, now); break;
    }
    case "queueClear": {
      const keep = p.queueIndex >= 0 ? p.queueIndex + 1 : 0;
      if (p.queue.length <= keep) return no("no_upcoming", "No upcoming tracks to clear");
      p.queue.length = keep; touchQueue(p, by, now); break;
    }
    case "queueMove": {
      let from = num(frame.fromIndex);
      const to = num(frame.toIndex);
      if (from === null || to === null) return no("missing_indices", "queueMove requires fromIndex and toIndex");
      if (typeof frame.videoId === "string" && frame.videoId) {
        from = p.queue.findIndex((t) => t.videoId === frame.videoId);
        if (from < 0) return no("invalid_move", "Invalid queue move indices");
      }
      if (from < 0 || from >= p.queue.length || to < 0 || to >= p.queue.length || from <= p.queueIndex || to <= p.queueIndex) return no("invalid_move", "Invalid queue move indices");
      if (from !== to) { const [item] = p.queue.splice(from, 1); p.queue.splice(to, 0, item); touchQueue(p, by, now); }
      break;
    }
    case "next":
    case "previous": {
      const target = p.queueIndex + (action === "next" ? 1 : -1);
      if (target < 0 || target >= p.queue.length) return action === "next" ? no("end_of_queue", "Already at the end of the queue.") : no("start_of_queue", "Already at the beginning of the queue.");
      setTrack(p, by, p.queue[target], 0, true, target, member.displayName, now); break;
    }
    case "setMaxMembers": {
      const v = num(frame.maxMembers);
      if (v === null) return no("invalid_capacity", "Choose a party size between 2 and 10.");
      if (!member.isHost) return no("host_only", "Only the host can change the party size.");
      if (v < 2 || v > 10) return no("invalid_capacity", "Party size must be between 2 and 10.");
      if (v < party.members.length) return no("party_too_small", "Party size cannot be smaller than the current member count.");
      party.maxMembers = v; break;
    }
    case "setAutoplay":
      if (typeof frame.enabled !== "boolean") return no("invalid_autoplay", "AutoPlay must be enabled or disabled.");
      if (p.autoplayEnabled !== frame.enabled) { p.autoplayEnabled = frame.enabled; p.updatedBy = by; p.updatedAtMs = now; }
      break;
    case "setHostOnlyControl":
      if (typeof frame.enabled !== "boolean") return no("invalid_control_policy", "Host-only control must be enabled or disabled.");
      if (!member.isHost) return no("host_only", "Only the host can change who controls the music.");
      party.hostOnlyControl = frame.enabled; break;
    case "kick": {
      const target = typeof frame.memberId === "string" ? frame.memberId : "";
      if (!member.isHost) return no("host_only", "Only the host can remove listeners.");
      if (!target || target === member.memberId) return no("invalid_member", "Choose another listener to remove.");
      if (!remove(party, target)) return no("not_found", "That listener is no longer in this party.");
      return { ok: true, kicked: target };
    }
    default:
      return no("unknown_action", `Unknown control action '${action}'`);
  }
  return { ok: true };
}

export function activityFrame(member: Member, frame: Frame, now: number) {
  const action = String(frame.action);
  const title = (t: unknown) => (t && typeof t === "object" && typeof (t as Frame).title === "string" ? (t as Frame).title as string : "");
  const tracks = Array.isArray(frame.tracks) ? frame.tracks : [];
  const fixed: Record<string, string> = {
    queueRemove: "Removed a track from the queue", queueClear: "Cleared upcoming tracks", queueMove: "Reordered the upcoming queue",
    next: "Skipped to the next song", previous: "Went back to the previous song", play: "Started playback", pause: "Paused playback",
    seek: "Changed the playback position", kick: "Removed a listener from the party", setMaxMembers: "Changed the party size", setAutoplay: "Changed AutoPlay",
  };
  let detail = fixed[action] ?? action;
  if (action === "setTrack" && title(frame.track)) detail = `Changed the song to "${title(frame.track)}"`;
  if (action === "queueAdd" && tracks.length > 0 && title(tracks[0])) {
    detail = tracks.length === 1 ? `Added "${title(tracks[0])}" to the queue` : `Added "${title(tracks[0])}" and ${tracks.length - 1} more to the queue`;
  }
  if (action === "setQueue" && Array.isArray(frame.queue)) detail = `Replaced the queue with ${frame.queue.length} tracks`;
  return { type: "activity", action, by: member.displayName, detail, atMs: now };
}
