# Nuvx Sync and device protocols (API v1)

Implemented by `core/sync`. **No client uses it yet.** Sync is opt-in and every client must keep working without it.

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

## QR pairing

1. The new device (a TV, say) calls `POST /v1/pairings`. It shows `code` as a QR and keeps `secret` to itself.
2. A trusted device scans the code, shows the user the new device's name, and on confirmation calls `approve`.
3. The new device polls `claim` with its secret and receives its own token.

The code expires after two minutes and works once. The code alone grants nothing, because `claim` needs the secret that never left the new device. No account credential is ever in the QR.

## Sync rules for clients

- **Idempotent push.** Retrying a push is always safe: an operation `id` that was already stored keeps its first sequence number.
- **Pull by cursor.** Keep the highest sequence applied; pull `after` it until `more` is false.
- **Deterministic merge.** Apply operations in sequence order. Two devices that edited the same playlist offline end up with the same result on both, because both replay the same order.
- **No silent loss.** Operations are appended, never overwritten, so earlier states can be rebuilt. That history is what playlist undo and restore will read.
- **Version field.** Responses carry `api`. A client that sees a higher number than it knows must stop writing and ask for an update, so an old client cannot corrupt newer data.

## Not built yet

Client integration on every platform, operation kinds and payload schemas for playlists, favorites and settings, conformance tests shared across clients, Nuvx Connect (remote control and handoff), and history compaction once a space nears its limit.

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
