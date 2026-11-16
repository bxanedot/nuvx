# Nuvx Sync on Oracle Cloud

Status: **built and tested in CI and locally; never deployed.** Nothing here has touched the server. The first run of any action needs the owner's approval in the `oracle-production` environment.

## What runs where

| Piece | Where | Why |
|---|---|---|
| Listen Together, presence, canvas, download counts | Cloudflare Worker (`core/worker`), unchanged | Works today; real-time rooms fit Durable Objects |
| Nuvx Sync API (`core/sync`): spaces, device pairing, operation log, export, delete | Oracle VM | Needs durable storage, which the Worker does not have |
| Music streams | Never the Oracle VM | The API carries metadata only |

Each kind of data has one owner. Party state lives in the Worker. Synced library data lives in the Oracle database. Clients keep their local library as the source for playback and work without either service.

## The service

- One Node process, no npm dependencies (`node:http`, `node:sqlite`). About 75 MB of memory idle; capped at 256 MB by systemd.
- SQLite file at `~/nuvx-sync/data/nuvx-sync.db`. No database port exists.
- Listens on `127.0.0.1:8787` only. HTTPS is terminated by a reverse proxy (see bootstrap).
- Logs go to the user journal, which rotates itself. Request bodies, headers and tokens are never logged.
- Limits: 256 KB request body, 200 operations per push, 32 KB per operation, 200,000 operations per space, 20 devices per space, 120 requests a minute per address.

API contract: [`docs/architecture/SYNC_AND_CONNECT.md`](architecture/SYNC_AND_CONNECT.md).

## One-time server preparation (needs an admin, not `nuvxdeploy`)

The deploy user stays unprivileged. It needs no sudo and no Docker. An admin runs these once:

1. Let the deploy user's service run without a login session:

   ```bash
   sudo loginctl enable-linger nuvxdeploy
   ```

2. Install a reverse proxy for HTTPS. Caddy gets and renews certificates by itself:

   ```bash
   sudo apt-get update && sudo apt-get install -y caddy
   ```

   `/etc/caddy/Caddyfile`, with the real host name:

   ```
   sync.example.com {
       reverse_proxy 127.0.0.1:8787
       request_body { max_size 256KB }
   }
   ```

   ```bash
   sudo systemctl reload caddy
   ```

3. Open ports 80 and 443: in the Oracle security list for the subnet, and in the instance firewall (Oracle's Ubuntu images block them by default).

4. Point a DNS record at the instance. **Owner decision:** which host name.

Run the workflow's `check` action afterwards. It reports anything still missing and changes nothing.

## Deploying

Actions → **Sync backend deploy (Oracle)** → Run workflow → pick an action. Each one waits for the `oracle-production` environment's approval.

| Action | What it does |
|---|---|
| `check` | Read-only preflight: lingering, systemd user session, disk, memory. |
| `deploy` | Tests and packages on a GitHub runner, uploads, snapshots the database, switches the `current` link, restarts, and checks health. If health fails it switches back to the previous release by itself. |
| `rollback` | Snapshots the database and returns to the previous release. |
| `backup` | Takes a snapshot, encrypts it on the runner and stores it as a workflow artifact for 30 days. Needs a `NUVX_BACKUP_PASSPHRASE` secret in the environment. |

Releases live in `~/nuvx-sync/releases/<commit>`. The last five are kept. Nothing is built on the server.

## Migrations and rollback

Migrations are forward-only and additive, run inside a transaction at start-up, and are preceded by a snapshot. Because they only add, the previous release still runs against a migrated database, so `rollback` does not touch data. A migration that removes or rewrites anything must ship in two releases: first stop using it, then remove it.

## Restoring a backup

On the server, as `nuvxdeploy`:

```bash
systemctl --user stop nuvx-sync
```

```bash
cp ~/nuvx-sync/backups/<snapshot>.db ~/nuvx-sync/data/nuvx-sync.db
```

```bash
systemctl --user start nuvx-sync
```

For an off-server copy, download the artifact and decrypt it first:

```bash
gpg --decrypt nuvx-sync-backup.db.gpg > restored.db
```

The server keeps the newest 14 snapshots. Scheduled off-server backups are not set up: a schedule would touch production without a person starting it, so it waits for the owner's decision.

## Verified and not verified

- Verified: 8 API tests; a real HTTP smoke test; the deploy script run locally against stubbed `systemctl` for first deploy, second deploy with snapshot, a broken release that rolled itself back, manual rollback, and data surviving all of it.
- Not verified: anything on the real server, including the SSH step, lingering, systemd memory limits, Caddy and the encrypted backup path.
