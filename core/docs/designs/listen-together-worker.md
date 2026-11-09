# Listen Together on the Cloudflare Worker

Status: draft for Goldfish review (2026-11-08). Author: Claude for bxane.

## Problem

nuvx is now the upstream app's code base (Android phone, Android TV, Wear OS, web player + extension), renamed. Its
Listen Together clients speak the party protocol of the upstream Go server (`backend/` in the old tree, deployed on Render).
bxane wants one server: the existing Cloudflare Worker `nuvx-listen` (`worker/`), which today speaks an older, smaller
"room" protocol used only by the deleted Kotlin Multiplatform app. Nothing has been released, so the old room protocol
has no users to keep working.

The Go server also hosts two smaller things the clients call: the open-app counter (`POST /api/presence`,
`GET /api/stats/live`, badge) and the invite landing page (`GET /invite/{code}`).

## Decision

Port the Go server's HTTP + WebSocket API to the Worker, byte-compatible on the wire, so the Android, TV and web clients
need only their base URL changed. Delete the room protocol (`src/room.ts`, its tests, the `Room` Durable Object).
Keep the canvas upload route (`POST /canvas`) unchanged.

Rejected: rewriting the clients down to the room protocol. It has no guest controls, queue editing, kick, party size,
autoplay or host-only control, so roughly 3,000 lines of client code and UI would lose features.

Rejected: keeping the Go server. Two servers for one feature, and Render's free tier sleeps.

## Wire contract (unchanged from the Go server)

HTTP, all JSON, errors as `{error, message}` with the Go status codes:

| Route | Behaviour |
|---|---|
| `GET /`, `GET /healthz`, `GET /api/time` | service info / `{ok, serverMs}` / `{serverMs}` |
| `POST /api/parties` | body `JoinRequest`; rate limited per IP; creates a party, joins the caller as host; `201 {code, token, you, party}` |
| `POST /api/parties/{code}/join` | `200 {code, token, you, party}`; rejoin by `deviceId` keeps the member and rotates its token; `409 party_full` |
| `GET /api/parties/{code}` | bearer token; party snapshot |
| `GET /api/parties/{code}/preview` | no token; names, avatars, host name, counts only |
| `POST /api/parties/{code}/leave` | bearer token; removes member, sends it `bye{reason:left}`, broadcasts `members` |
| `GET /ws/parties/{code}` | token from `Authorization: Bearer` (apps) or subprotocol `nuvx.token.<hex>` with `nuvx.v1` answered (browsers) |
| `POST /api/presence`, `GET /api/stats/live`, `GET /api/stats/live/badge.svg` | open-app counter |
| `GET /invite/{code}` | HTML landing page that opens `nuvx://party/<code>?server=<origin>` |
| `POST /canvas` | unchanged |

`JoinRequest` validation is the Go `Validate()`: userId/deviceId 1–128 chars, displayName 1–80, avatarUrl http(s) ≤1000,
maxMembers 2–10, optional autoplayEnabled. Codes are 6 chars from `0123456789ABCDEFGHJKMNPQRSTUVWXYZ`; input is
normalised (upper case, strip non-alphanumerics, I/L→1, O→0).

WebSocket frames: client sends `ping{clientMs}`, `sync`, `syncQueue`, `report{positionMs}`, `control{action,…}`; server
sends `welcome{you,party,serverMs}`, `state{playback,serverMs}`, `queue{queue,serverMs}`, `members{members,maxMembers,
hostOnlyControl,serverMs}`, `pong{clientMs,serverMs}`, `error{error,message}`, `bye{reason,message?}`,
`activity{action,by,detail,atMs}`. Control actions and their rules are those of `applyControl` in the Go `main.go`
(play, pause, seek, setTrack, setQueue, queueAdd, queueRemove, queueClear, queueMove, next, previous, kick,
setMaxMembers, setAutoplay, setHostOnlyControl), including host-only enforcement, the 350 ms play lead,
`nearestIndexOf`, autoplay de-duplication, 25 upcoming tracks max, and per-member token buckets (30 frames/s,
25 controls/s). After a successful control: `queue` (if queueSeq changed), `state`, `members` (kick / size / policy),
then `activity`, to everyone.

## Architecture

- `src/party.ts`: pure logic ported from `party/party.go`, `protocol/protocol.go`, `codes/codes.go`, the control
  switch and frame builders of `main.go`. No Cloudflare APIs, so `node --test` covers it. Party state is a plain JSON
  object (members keyed by id, playback, queue).
- `src/index.ts`: routing, CORS, rate limits, and two Durable Objects:
  - `Party`, one per code (`idFromName(code)`). Holds the party JSON in DO storage (`"party"` key), rewritten after
    every mutation, so eviction or hibernation loses nothing. Uses the hibernation WebSocket API; each socket's
    attachment is `{memberId}`. An alarm runs every 5 s while any socket is open: re-sends `state` (heartbeat), removes
    members disconnected for more than 45 s, and expires the party (deleteAll + close sockets) when empty for 120 s or
    older than 12 h. With no sockets the alarm is set to the next expiry instant instead.
  - `Presence`, a single instance (`idFromName("global")`), keeps `{id → {platform, lastMs}}` in memory and counts
    entries seen in the last 390 s. Losing it on eviction only under-counts for one ping interval.
- Creating a party: the Worker picks a code, calls `Party.create(req)` on that DO; if the DO reports it already holds a
  live party, pick another code (up to 12 tries, then `503 code_exhausted`). There is no global party count, so the Go
  `JAM_MAX_PARTIES` cap is dropped; per-IP creation limit (Workers rate-limit binding, 2/min) stays.
- Clock: `Date.now()`. Cloudflare's clock does not step backwards within a request, so the Go monotonic wrapper is
  not ported.
- Allowed browser origins (CORS, WebSocket `Origin`, presence): `https://web.nuvx.lol`, `https://nuvx.lol`,
  `http://localhost:5173`. Native apps send no `Origin`.

## Hosts

- Worker: `https://nuvx-listen.bxane.workers.dev` (later `listen.nuvx.lol`). Apps' `LISTEN_TOGETHER_SERVER` default and
  `Presence.ENDPOINT` point here.
- Website (static, Vercel): `https://nuvx.lol`. `vercel.json` rewrites `/invite/:code` to the Worker's landing page, so
  shared links `https://nuvx.lol/invite/<CODE>` keep working; Android App Links for `nuvx.lol` need
  `/.well-known/assetlinks.json` on the website (release signing key fingerprint; added when the key exists).
- Web player: `https://web.nuvx.lol` (its own host, so the static website keeps nuvx.lol).

## Risks and limits

- Wire compatibility is checked by porting the Go unit tests that cover party logic (`party_test.go`) and the
  WebSocket flows in `main_test.go` that the clients rely on: create → join → ws welcome → control → broadcast,
  host-only refusal, kick, leave.
- A DO alarm every 5 s per active party: Cloudflare bills alarm invocations as requests; with the free plan's 100k/day
  that is about 5 continuously active parties. Acceptable for an unreleased app; raise the interval or move to paid if
  usage grows.
- Presence counts are approximate by design.
- Untested on real devices in this change: actual sync drift, background/doze reconnect.

## Implementation Plan

1. Add `src/party.ts` + `test/party.test.ts` (port of the Go tests that cover logic and validation).
2. Rewrite `src/index.ts` routes and the `Party` / `Presence` DOs; delete `src/room.ts`, `test/room.test.ts`, old e2e.
3. `wrangler.toml`: migration `v2` deletes class `Room`, adds `Party` and `Presence`; set creation limit to 2/min.
4. New `test/e2e.ts` against `wrangler dev`: create, join, websocket welcome, control broadcast, leave.
5. Point clients at the Worker: Android/TV `LISTEN_TOGETHER_SERVER` default, `Presence.ENDPOINT`, web player default
   server; website `vercel.json` invite rewrite.
6. `npm test`, `npm run check`, e2e pass; Android unit tests for listen-together still pass.

## Review resolutions (Goldfish design + implementation-readiness reviews, 2026-11-08)

These override anything above that disagrees.

- **Contract wording.** It is the Go contract with the upstream names replaced by nuvx: subprotocols `nuvx.v1` / `nuvx.token.`,
  deep links `nuvx://party/…` and `intent://…#Intent;scheme=nuvx;end`. Nothing else differs.
- **Membership is checked on every frame.** `webSocketMessage` reloads the party and looks the socket's member up by
  tag; a member that has left, been kicked or been swept gets `bye` and its socket is closed. Leave, kick and the
  sweep close the member's sockets.
- **`connected` is never stored.** Each socket is accepted with tag `memberId`; connected = `getWebSockets(memberId)`
  is non-empty. A new socket for a member closes that member's older sockets (Go `hub.Attach`). `members` is broadcast
  after `welcome` and again on close.
- **What is stored.** Only what clients can see: members (id, userId, deviceId, name, avatar, token, host, joinedAtMs,
  lastSeenMs), playback, queue, maxMembers, hostOnlyControl, createdAtMs, emptySinceMs. Token buckets live in memory
  per member (reset on wake, which only ever lets a member burst once more). `lastSeenMs` is written on join, connect
  and disconnect, the only times the sweep needs it.
- **No periodic alarm.** The heartbeat `state` is sent when a `ping` or `report` arrives and the last `state` went out
  more than 5 s ago (clients ping every 15 s). The alarm is set to the next expiry instant: min(disconnected member
  `lastSeenMs` + 45 s, `emptySinceMs` + 120 s, `createdAtMs` + 12 h). Expiry calls `deleteAlarm()` and `deleteAll()`
  and closes sockets. Cost then scales with frames, not wall time.
- **Rate limits (Workers rate-limit bindings, per IP).** `CREATE_LIMIT` 2/60 s on create; `PARTY_LIMIT` 60/60 s on
  every other party, websocket and invite route (stops code scanning from burning the DO quota); `PRESENCE_LIMIT`
  20/60 s; `CANVAS_LIMIT` unchanged. Request bodies and websocket frames over 16 KiB are rejected.
- **Codes are normalised in the Worker before `idFromName`**; an invalid code is `404 no_such_party` (`400` page for
  the invite route).
- **Party DO interface.** RPC methods return `{status, body}` that the Worker turns into JSON with CORS:
  `create(req)` (returns `{exists:true}` if the code is taken), `join(req)`, `snapshot(token)`, `preview()`,
  `leave(token)`, `invite(origin)` (returns page data); `/ws/parties/{code}` is forwarded with `stub.fetch`.
  Errors are `{error, message}` with Go's status codes.
- **Presence.** `POST /api/presence` body `{id: UUID, platform: android|pc|browser, open: bool}`; 422 `invalid_id` /
  `invalid_platform` / `invalid_open`; 403 `origin_not_allowed` unless (platform is browser) == (Origin present) and any
  Origin is allowed; `open:false` deletes the entry; reply `{intervalSec: 300}`. `GET /api/stats/live` →
  `{online, android, pc, browser, updatedAt}` with `access-control-allow-origin: *`, `cache-control: public,
  max-age=15`; badge SVG is Go's `handleLiveBadge` template. The Presence DO keeps one SQLite row per install
  (upsert `last_ms`), counts rows newer than 390 s, and deletes older rows on each count.
- **Invite page.** Go's `inviteTemplate`, rebranded to nuvx; origin = `new URL(req.url).origin`; download link
  `https://github.com/bxanedot/nuvx/releases`, plus "open in the web player" `https://web.nuvx.lol/invite/<code>`.
- **`report`** only triggers the heartbeat; drift is not logged (the Worker logs nothing).
- **`GET /`** returns `{service: "nuvx-listen-together", serverMs}`; per-process member/party counts do not exist on
  Durable Objects and nothing reads them.
- **wrangler.toml.** Bindings `PARTIES → Party`, `PRESENCE → Presence`; migration `v2`: `deleted_classes = ["Room"]`,
  `new_sqlite_classes = ["Party", "Presence"]`; rate limits above.
- **Clients.** `LISTEN_TOGETHER_SERVER` already defaults to the Worker. `Presence.ENDPOINT` (shared
  `data/presence/Presence.kt`) becomes `https://nuvx-listen.bxane.workers.dev/api/presence`. The web player reads
  `VITE_PARTY_SERVER_URL`; its `.env.example` documents the Worker URL. Website `vercel.json` rewrites
  `/invite/:code` to the Worker.

### Done when

1. `test/party.test.ts` passes: validation, codes, play/pause/seek lead, setTrack/setQueue index rules, queue
   add/remove/clear/move/limits, autoplay de-dupe, host-only refusal, host hand-over on remove, expiry.
2. `npm test` (party, canvas, canvas-route) and `npm run check` (wrangler dry-run build) pass.
3. `test/e2e.ts` against `wrangler dev` passes: create → join → both sockets get `welcome` → guest `control play`
   reaches host as `state` → host enables host-only → guest control refused `host_only` → host kicks guest (guest gets
   `bye kicked`) → leave → presence ping + stats.
