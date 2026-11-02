# Nuvx Listen Together

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
| `{t:"state", trackId, positionMs, playing, queue?}` | host | `trackId` and `queue` items must be 11-character YouTube video IDs; `queue` holds at most 200 |
| `{t:"handoff", to:<memberId>}` | host | makes another member host |

Server → client: `welcome {you, s, room}`, `members {host, members}`, `state {s, playback}`, `pong`, `error {code}`.

**Latency compensation.** When a client receives `state`, it plays from `positionMs + (serverNow − updatedAt)` if `playing`, where `serverNow = Date.now() + clockOffset`.

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

This needs the owner's Cloudflare login (`npx wrangler login`) or a `CLOUDFLARE_API_TOKEN` secret in CI. To use `listen.nuvx.lol`, add nuvx.lol to that Cloudflare account and uncomment `routes` in `wrangler.toml`.
