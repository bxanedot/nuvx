# Nuvx Sync and device protocols (API v1)

Implemented by `core/sync` (a server you host) and by every **LAN host**: the desktop app (`app/desktop/src/lan_sync/`) and, when turned on, an Android phone (`LanHost.kt`). Android is the client in both cases. Sync is opt-in and every client must keep working without it.

## Model

- A **space** is one person's set of trusted devices. There is no account, email or password.
- A **device** holds a random bearer token. The server stores only its SHA-256. Revoking a device deletes the hash.
- An **operation** is one change to synced data, with a client-chosen `id`. The server gives each accepted operation the next sequence number in the space. That sequence is the single agreed order for every device.

The server never interprets payloads. Clients must not put provider cookies, tokens or passwords in them.

## Endpoints

All bodies are JSON. Errors are `{ "error": code, "message": text }`.

| Call | Auth | Result |
|---|---|---|
| `GET /healthz` | none | `{ ok, api, schema }` |
| `POST /v1/spaces` `{ deviceName }` | none | `201 { spaceId, deviceId, token }` |
| `POST /v1/pairings` `{ deviceName }` | none | `201 { code, secret, expiresAtMs }` |
| `POST /v1/pairings/{code}/approve` | device | `{ ok, deviceName }` |
| `POST /v1/pairings/{code}/claim` `{ secret }` | none | `202 { pending }` until approved, then `200 { spaceId, deviceId, token }` once |
| `GET /v1/devices` | device | `{ devices: [{ id, name, createdMs, seenMs }], you }` |
| `DELETE /v1/devices/{id}` | device | revokes that device |
| `POST /v1/ops` `{ ops: [{ id, kind, entity, payload, clientMs? }] }` | device | `{ seqs }`, one per op |
| `GET /v1/ops?after={seq}` | device | `{ api, ops, more }`, up to 500 in order |
| `GET /v1/export` | device | every operation in the space |
| `DELETE /v1/space` | device | deletes the space, its devices and its data |

Limits: a space holds 20 devices and 20 MB or 200,000 operations (`413 space_full`). The whole server stops taking new spaces and pushes at 10 GB of data (`507 server_full`); reads, export and deletion keep working, so a client should keep its outbox and retry later.

## QR pairing

1. The new device (a TV, say) calls `POST /v1/pairings`. It shows `code` as a QR and keeps `secret` to itself.
2. A trusted device scans the code, shows the user the new device's name, and on confirmation calls `approve`.
3. The new device polls `claim` with its secret and receives its own token.

The first approval wins: a second space that approves the same code gets `409 already_approved`, so someone who saw the QR cannot pull the new device into their own space. The 20-device cap is checked again at `claim`. Before uploading anything, the new device should list `GET /v1/devices` and ask the user to confirm that these are their devices.

The code expires after two minutes and works once. The code alone grants nothing, because `claim` needs the secret that never left the new device. No account credential is ever in the QR.

## Sync rules for clients

- **Idempotent push.** Retrying a push is always safe: an operation `id` that was already stored keeps its first sequence number.
- **Pull by cursor.** Keep the highest sequence applied; pull `after` it until `more` is false.
- **Deterministic merge.** Apply operations in sequence order. Two devices that edited the same playlist offline end up with the same result on both, because both replay the same order.
- **No silent loss.** Operations are appended, never overwritten, so earlier states can be rebuilt. That history is what playlist undo and restore will read.
- **Version field.** Responses carry `api`. A client that sees a higher number than it knows must stop writing and ask for an update, so an old client cannot corrupt newer data.

## LAN hosts (no server, no account)

A computer, or a phone with **Settings → Sync & Connect → Let other phones pair with this phone** on, serves the
same v1 log to devices on its network. Everything below rides one encrypted channel, so it all inherits the
pairing, approval and revocation.

- **Finding the host.** `GET /lan/hello` answers `{ app, api, name, id, code, bt }`: a fresh 4-digit `code` the host
  shows (it only locates the host, it grants nothing) and its Bluetooth address when its listener runs. A phone
  typing the code probes its own Wi-Fi /24 (never a mobile carrier's network), then computers it is paired with over
  Bluetooth.
- **Pairing.** P-256 ECDH. The phone first commits to its key (SHA-256), the host answers with its one-time key, the
  phone reveals; a wrong reveal ends the attempt. HKDF-SHA256 (salt: phone key ‖ host key) gives the device key and
  a 6-digit code both screens show; the host approves only after comparing. Requests expire after 5 minutes, at most
  4 wait at once, claims are single use. Rust (`crypto.rs`) and Kotlin (`LanSyncCrypto`, both roles) are byte
  compatible.
- **Channel.** `POST /lan/v1` carries `{ m, p, t, n, b }` sealed with AES-256-GCM, bound to the device id; the reply
  is sealed and bound to the request nonce. Timestamps outside ±2 minutes and reused nonces are refused. Removing a
  device on the host deletes its key: its next request gets 401.
- **Routes inside the channel.** The v1 sync routes, plus:
  - `GET /v1/connect` (what the host plays) and `POST /v1/connect` (`toggle`, `play`, `pause`, `next`, `previous`,
    `seek`, `load` with 1–20 YouTube songs from a position, paused or playing). Off until the host turns on **Allow
    remote control** (403 meanwhile). Cover art only from YouTube image hosts over https.
  - `GET /v1/files` (the host's single-file downloads), `GET /v1/files/{videoId}?offset=` (1 MB pieces),
    `POST /v1/files/{videoId}` (192 KB pieces in order from one device, 500 MB cap, at most 4 at once). Songs are
    named by video id only, never by path.
- **Transports.** TCP on the host's Wi-Fi (local peers only, port 47731 or the next free one), or Bluetooth RFCOMM
  service `6e757678-4c61-6e53-796e-630000000001` (Windows hosts; one kept-open connection for many requests).
  Android prefers Wi-Fi, switches to Bluetooth when the paired host's id answers only there, and back when it is
  on Wi-Fi again.
- **Nuvx Connect on Android** is a speaker in the player's output sheet (`ComputerSpeaker`, the same contract as Cast
  and the watch): the phone keeps the queue, the host plays the current song and the next two, and the phone's
  player, notification and lock screen control it. A hosting phone is never its own speaker.

## Not built yet

**Android** syncs nuvx playlists (opt-in, Settings → Your data → Nuvx Sync): `SyncEngine` (`core/shared/.../data/sync/SyncEngine.kt`) finds local changes by comparing with the last synced snapshot, keeps them in an outbox until the server stores them, replays the whole log, and the app applies the result only if the playlists were not edited meanwhile. HTTPS servers only. Pairing shows a QR and an 8-character code; the other device types the code (no camera scanner yet). Still to do: server Sync on iOS, desktop and web; LAN hosting and Bluetooth on macOS/Linux and iOS; the desktop controlling a phone (Connect is phone → host only); a camera QR scanner; favorites and settings; shared conformance tests; history compaction once a space nears its limit.

## Operation kinds (v1)

Clients push these ops; the server stores them in order and never interprets them. Every client replays the log with the same rules and must reproduce `core/sync/fixtures/replay-v1.json`. The Kotlin reference is `core/shared/.../data/sync/SyncReplay.kt` (Android and JVM); iOS, desktop and web ports must pass the same fixture.

| Kind | Entity | Payload | Rule |
|---|---|---|---|
| `playlist.create` | `playlist:<id>` | `title` | Ignored if the id exists or was ever deleted |
| `playlist.rename` | `playlist:<id>` | `title` | Later in server order wins |
| `playlist.delete` | `playlist:<id>` | — | Final; later ops on that id are ignored |
| `playlist.addTrack` | `playlist:<id>` | `videoId`, `after` | `after` absent appends, `null` puts it first, an id places it after that track; a missing anchor appends; a track already present is not added again |
| `playlist.moveTrack` | `playlist:<id>` | `videoId`, `after` | Same placement rules; ignored if the track is gone |
| `playlist.removeTrack` | `playlist:<id>` | `videoId` | Ignored if the track is gone |
| `favorite.set` | `favorite:<videoId>` | `on` | Later in server order wins |
| `setting.set` | `setting:<key>` | `value` | Later in server order wins |

- **Unknown kinds:** skipped, so an older client never corrupts state written by a newer one.
- **Restoring a version:** replay the log up to its `seq`.
