# nuvx Listen Together

**Live:** https://nuvx-listen.bxane.workers.dev (deployed 2026-11-02; e2e test passes against it).

The optional upcoming-track metadata extension below is implemented in source and tested locally. It has not been deployed; the hosted service still relays upcoming IDs only.

A Cloudflare Worker with one Durable Object per room. Rooms sync **track IDs and playback state only**, never audio: each device streams through its own engine.

## API

| | |
|---|---|
| `POST /rooms` | creates a room → `{ "code": "K7QM2X" }` (rate limited to 10/min per IP) |
| `GET /rooms/{code}/ws?name=…` | WebSocket; returns 404 if the room doesn't exist |

Client → server

| message | who | |
|---|---|---|
| `{t:"ping", c:<clientMs>}` | anyone | server replies `{t:"pong", c, s:<serverMs>}`; clock offset ≈ `s − (c + now)/2` |
| `{t:"state", trackId, positionMs, playing, queue?}` | host | `trackId` and `queue` items must be 11-character YouTube video IDs; `queue` holds up to 200 upcoming IDs after the current track, in playback order (duplicates allowed) |
| `queueInfo?: [{trackId, title, artists}]` in `state` | host | optional display metadata for up to 200 unique IDs already in `queue`; each title is at most 200 characters, with up to 10 artist names of 100 characters each; the serialized metadata array is limited to 64 KiB of UTF-8 |
| `{t:"handoff", to:<memberId>}` | host | makes another member host |

Server → client: `welcome {you, s, room}`, `members {host, members}`, `state {s, playback}`, `pong`, `error {code}`.

**Latency compensation.** When a client receives `state`, it plays from `positionMs + (serverNow − updatedAt)` if `playing`, where `serverNow = Date.now() + clockOffset`.

The KMP client publishes queue and metadata-only edits even when the current track stays paused. Guests replace only their upcoming queue and current display metadata, preserving playback position and earlier queue items. On-device tracks, local URIs, and malformed IDs are excluded. The Worker accepts only the three documented metadata fields; URLs and other properties are never relayed. Client messages over 128 KiB of UTF-8 are rejected, and the client ignores server frames over that limit.

Metadata is optional for compatibility with older clients and the currently deployed Worker. Clients reuse locally known names or show “Shared track” when metadata is absent or exceeds its byte budget; IDs remain queued in either case. New clients always send an empty queue and metadata array when cleared. A legacy host state without metadata clears any earlier host's metadata. Generic fallback labels are not allowed to overwrite a known catalog title.

**Rules.** The first joiner hosts. When the host leaves, the longest-present member takes over. Rooms hold at most 50 members. Each socket may send 20 messages in a burst, refilled at 2/s. A room is deleted after 30 minutes with nobody connected. Names are trimmed to 32 characters with control characters removed. Nothing is logged.

## Develop

```bash
npm ci
npm test                 # room logic (node --test)
npx wrangler dev         # local runtime, then in another shell:
npm run e2e              # two real WebSocket clients against wrangler dev
npm run check            # bundle + binding check without deploying
```

## Deploy

`npx wrangler login`, then `npm run deploy` (or a `CLOUDFLARE_API_TOKEN` secret in CI). The worker is `nuvx-listen` on the `bxane` workers.dev subdomain. It is separate from the `nuvx` worker on the same account, which serves a copy of the website.

nuvx.lol is hosted on Vercel, so `listen.nuvx.lol` would need a CNAME at Vercel DNS pointing to the worker plus a Cloudflare custom domain, which only works for zones on Cloudflare. Clients use the workers.dev URL until that changes.

The `CREATE_LIMIT` binding is Cloudflare's approximate limiter. In a live burst of 30, about 7 requests passed before it returned 429.
