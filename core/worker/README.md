# nuvx Listen Together worker

**Live:** https://nuvx-listen.bxane.workers.dev, running the party protocol in this folder.

A Cloudflare Worker that serves:

- **Listen Together parties**: one Durable Object (`Party`) per six-character code. Parties sync track ids, the queue
  and playback state, never audio; every device streams on its own.
- **Live stats**: `GET /api/stats/live` reports `online` from recent app presence pings and `listening` from
  connected devices in parties with playback running. `POST /api/presence` updates the online count.
  `GET /api/stats/live/badge.svg` displays the online count.
- **Release downloads**: `GET /api/stats/downloads` sums GitHub download counts for published nuvx app packages.
  It refreshes at most every five minutes and serves the last successful total if GitHub is unavailable.
- **Invite pages**: `GET /invite/{code}`, which nuvx.lol rewrites here.
- **Canvas uploads**: `POST /canvas` from the canvas.nuvx.lol page (commits `core/canvas/<videoId>.mp4` to this repo).

The wire contract is the upstream Go party server's, with its names replaced by nuvx. Routes, frames, limits and the reasoning are
in [docs/designs/listen-together-worker.md](../docs/designs/listen-together-worker.md); the party logic is
`src/party.ts`.

**Rules.** The first joiner hosts; when the host leaves, the longest-present member takes over. Parties hold 2–10
devices (5 by default). Anyone may control the music unless the host turns on host-only control. A member
disconnected for 45 s is removed; a party is deleted 2 minutes after its last device disconnects, or after 12 hours.
Each member may send 30 frames and 25 controls per second. Per IP: 2 party creations, 60 other party requests and
20 presence pings per minute. The approximate live counter accepts at most five active install IDs per hashed IP;
stale rows are removed on the next stats query or open ping. Bodies and frames are limited to 16 KiB. The worker does not log raw IPs or request bodies.

## Develop

```bash
npm ci
npm test                 # party logic + canvas (node --test)
npx wrangler dev         # local runtime, then in another shell:
npm run e2e              # real WebSocket clients against wrangler dev
npm run check            # bundle + binding check without deploying
```

## Deploy

`npx wrangler login`, then `npm run deploy` (or a `CLOUDFLARE_API_TOKEN` secret in CI). The deploy applies migration
`v2`, which deletes the old `Room` Durable Object class and its data.

nuvx.lol is hosted on Vercel, so `listen.nuvx.lol` would need a CNAME at Vercel DNS pointing to the worker plus a
Cloudflare custom domain, which only works for zones on Cloudflare. Clients use the workers.dev URL until that changes.

Canvas uploads need two secrets: `wrangler secret put CANVAS_PASSCODE` and `wrangler secret put CANVAS_GITHUB_TOKEN`.
