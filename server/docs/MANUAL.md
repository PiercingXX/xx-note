# xxnote-server — Operator/Developer Manual

xxnote-server is the fabric backend for `xx-note`: a single static Go binary
(stdlib only) that serves each estate user's notes as plain `.md` files under
`<data>/users/<user_id>/vault/`, authenticated against the estate fabric
identity — the server half of moving `xx-note` off direct-to-Synology WebDAV
and onto the shared fabric login, modeled on `xx-drive`'s shape and security
posture. This manual documents **verified behavior only** — read directly
from this repo's source (`internal/api/server.go`, `internal/fabric/token.go`,
`internal/vault/vault.go`, `cmd/xxnote-server/main.go`, `server/README.md`)
and cross-checked against the single-node deploy record (2026-08-25). If this
manual and the code disagree, the code wins — file a fix.

## 1. What it is & its role

- **Job**: per-user `.md` notes vault server on the estate fabric, sitting
  behind the Android app's `RemoteFiles` port. The storage philosophy of the
  app is preserved — a user's notes are still just a folder of `.md` files
  (plus `attachments/`) on disk, so an operator with shell access can `cat`
  them directly; only the transport and auth changed (from Synology WebDAV +
  DSM credentials to the fabric HTTP API + a bearer token).
- **Client-side impact is minimal by design**: the server's endpoints mirror
  the Android app's `RemoteFiles` port
  (`app/src/main/java/com/piercingxx/xxnote/sync/Ports.kt`) 1:1, so swapping
  backends is a single new `RemoteFiles` implementation
  (`FabricFilesClient`), with zero change to `SyncEngine`, `SyncPolicy`,
  `MergeEngine`, or `VaultStore`. See §5 for that follow-up.
- **Single static binary, stdlib only, modeled on `xx-drive`.** No external
  Go modules — `go.mod` declares no `require` block.

## 2. Architecture

### 2.1 Auth — fabric token validated locally in Go

`internal/fabric` (package `fabric`) validates a **ClusterKeyring v1** bearer
token entirely locally — no call back to `xx-chat` — reproducing the token
format byte-for-byte from `skippy-tel-network/syncdaemon/session_keys.py`:

```
v1.<key_id>.<b64url(claims_json)>.<b64url(hmac_sha256(secret, signed_part))>
signed_part = "v1.<key_id>.<b64url(claims_json)>"
```

- `Keyring.Validate(token, now)`: splits into 4 dot-separated parts, requires
  the `v1` version tag, looks up the signing secret by `key_id` in the
  keyring, recomputes the HMAC-SHA256 over the exact `signed_part` string,
  constant-time compares (`hmac.Equal`), decodes the claims, and rejects if
  `now >= exp` (epoch-seconds compare, matching the Python `moment >= exp`
  semantics) or if `user_id` is empty.
- `Keyring.UserIDFor(authHeader, now)` is the single call every handler makes
  to learn the caller's identity — parses `Authorization: Bearer <token>`,
  validates, and returns the claimed `user_id` or `ErrAuth`.
- `LoadKeyring(path)` reads the operator-provisioned ring
  (`{"keys": {key_id: secret_hex}, "active_key_id": ...}` — the same JSON
  `ClusterKeyring.save` writes on the Python side) from `path`, or from
  `FABRIC_CLUSTER_KEYS_PATH` if `path` is empty. This node is
  **validate-only**: it never signs and holds no active key, only the ring of
  accepted keys. A missing/unreadable/malformed ring is a **fatal boot
  error** (`main.go` calls `log.Fatalf` before ever constructing the vault
  store or binding a port) — fail closed, never serve a request no token
  could satisfy anyway.
- **Cross-language pinning**: `internal/fabric/token_test.go`'s
  `TestGoldenVectorFromPythonMinter` is generated from the real
  `xx-chat/xxchat/fabric_tokens.py` minter, proving byte-for-byte format
  compatibility with the Python validator
  (`skippy-tel-network/syncdaemon/fabric_auth.py`) rather than an
  independently-drifted reimplementation.

### 2.2 Per-user isolation — the choke point

`vault.Store.ResolveUserPath(userID, rel)` (`internal/vault/vault.go`) is the
**single** path-derivation function every handler goes through — adapted
from `xx-drive`'s `fsdrv.ResolveUserPath`. Every authenticated handler passes
the `user_id` from the validated token **only** — never a URL param, body
field, or client header — as `userID`.

`ResolveUserPath`:

1. Rejects a `userID` that is not a safe single path segment (`.`, `..`, any
   `/`/`\`/NUL).
2. Lexically cleans and vets the request path (`ValidateRel`): rejects NUL
   bytes, backslashes, percent-encoded separators/dots (`%2f`, `%5c`, `%2e`
   — a double-decode confusion vector), over-long segments (>255 chars) or
   overall path (>4096 chars); `filepath.Clean("/" + rel)` collapses any
   `..` that tries to climb above the vault root back down to `/`.
3. Joins under `<root>/users/<userID>/vault` and asserts the lexical result
   stays at or below that root.
4. Walks the path component-by-component with `os.Lstat`, refusing any
   symlink component — so a planted symlink inside a vault cannot bridge
   outside the tree, even past the lexical check.

### 2.3 HTTP surface

`internal/api/server.go`. Every `/api/v1/*` route is wrapped by `s.authed(...)`
— a missing/invalid/expired token is `401` before the handler runs, and the
handler receives the validated `uid` as a parameter (not something it looks
up itself). `securityHeaders` middleware sets `X-Content-Type-Options:
nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` on every
response; `recover` middleware turns a handler panic into a `500` instead of
crashing the process.

| `RemoteFiles` method | HTTP | Notes |
|---|---|---|
| `list(dir)` | `GET /api/v1/list?dir=<dir>` | JSON `{"entries":[{name,etag,size,dir}]}`; a missing vault reads as an empty listing |
| `get(path)` / `getFile(path)` | `GET /api/v1/file/{path...}` | raw bytes + `ETag` header, or `404`; text and binary share one path space |
| `put` / `putIfAbsent` / `putFile` | `PUT /api/v1/file/{path...}` | `If-Match: <etag>` → conditional write, `412` on mismatch; `If-None-Match: *` → create-only, `412` if it exists; neither header → unconditional overwrite/create |
| `move(from, to, overwrite)` | `POST /api/v1/move` `{from,to,overwrite}` | `204`; `overwrite:false` onto an existing destination → `412` |
| `delete(path)` | `DELETE /api/v1/file/{path...}` | `204` / `404` |
| `mkcol(dir)` | `POST /api/v1/mkcol` `{dir}` | idempotent `204` |
| health | `GET /healthz` | `200 ok`, plain text, **no auth** — matches `xx-drive`/radio's convention |

`ETag` is the strong content hash — `"<sha256-hex of the file bytes>"` — so
`SyncEngine`'s `If-Match`/412 conflict path works unchanged from the WebDAV
backend it replaces.

### 2.4 On-disk layout

```
<data>/users/<user_id>/vault/...    # plain .md files + attachments/, per user
```

`vault.Store.New(dataDir)` creates `<dataDir>/users` (mode 0700) at startup
and resolves the root through `filepath.EvalSymlinks` once. Writes
(`Store.Put`) go through a temp-file-then-`os.Rename` sequence (mode 0600) so
a crash mid-write never corrupts the destination file or leaves a partial
read visible.

## 3. How it's run

| Field | Value |
|---|---|
| Port | **8746** (127.0.0.1 only) |
| Health | **`GET /healthz`** → `200 ok`, no auth |
| Data dir | **`/srv/deep/xxnote`** (ZFS pool — never a relative path, never the install dir; same precedent as Jal's `/srv/deep/jal`, see parent `AGENTS.md`'s 2026-08-22 note) |
| Data dir flag/env | `-data` / `XXNOTE_DATA_DIR` (default `/srv/deep/xxnote`) |
| Bind flag/env | `-addr` / `XXNOTE_ADDR` (default `127.0.0.1:8746`) |
| Auth env | `FABRIC_CLUSTER_KEYS_PATH` (or `-keyring`) — the operator-provisioned `ClusterKeyring` JSON. Fails closed at boot if missing/unreadable. |
| Binary | `cmd/xxnote-server` — one static Go binary, no CGO, no external modules |

### Build

```bash
go build ./...   # clean
go vet ./...     # clean
go test ./...    # all green
```

### Run

```bash
xxnote-server -addr 127.0.0.1:8746 -data /srv/deep/xxnote
```

### systemd (`server/deploy/xxnote-server.service`)

Runs as a dedicated `xxnote` system user. Hardening: `NoNewPrivileges`,
`ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`,
`ReadWritePaths=/srv/deep/xxnote` (only the data dir is writable),
`ReadOnlyPaths=/etc/xxnote` (the keyring dir), `ProtectKernelTunables`,
`ProtectControlGroups`, `RestrictSUIDSGID`, `MemoryDenyWriteExecute`,
`LockPersonality`, `Restart=on-failure` / `RestartSec=5`. The unit's
`FABRIC_CLUSTER_KEYS_PATH` points at `/etc/xxnote/cluster-keys.json` — the
single-node deploy record instead uses a shared ring at
`/srv/deep/skippy-tel-deploy/fabric-keys.json`; either location works, the
env var is what matters.

## 4. Security / isolation model

- **Identity**: fabric bearer token only, validated locally in Go
  (§2.1) — no dependency on `xx-chat`'s uptime for every request, only for
  minting a fresh token.
- **Isolation**: the `ResolveUserPath` choke point (§2.2) is the *only* way
  any handler ever touches disk; the `userID` argument to it always comes
  from the validated token, never from any client-controlled input.
- **Adversarial proof**:
  - `internal/vault/vault_test.go::TestTraversalCorpus` — the classic
    payload corpus (`../`, `%2e%2e`, absolute paths, backslashes,
    sibling-user reach attempts, NUL, over-long segments) all contained.
  - `internal/vault/vault_test.go::TestSymlinkEscape` — a symlink planted
    inside a vault cannot read outside it.
  - `internal/api/server_test.go::TestTwoUserIsolation` — two real tokens
    (A and B); A's token can never read, overwrite, delete, or move B's
    sentinel note, across the full endpoint matrix. Verified to go RED when
    the choke point is disabled.
  - `internal/api/server_test.go::TestForgedTokenRejected`,
    `TestExpiredTokenRejected`, `TestUnauthenticatedRejected` — auth-layer
    adversarial cases.
- **Deploy-time isolation smoke** (2026-08-25, live): user A PUT+list+GET her
  own note OK; user B's list of the same path was empty; B's GET of A's note
  was `404`; an unauthenticated request was `401`.
- **Fail-closed boot**: a missing/unreadable/malformed cluster keyring stops
  the process before it binds a port (§2.1) — never a window where the
  server accepts connections no token could satisfy.

## 5. The Android client follow-up (documented, not built)

Full plan: `server/docs/ANDROID-FABRIC-CLIENT.md`. Summary — **not built**
here (no Android toolchain, JDK, or SDK in the server build environment):

1. **`net/FabricFilesClient.kt`** — a new `RemoteFiles` implementation
   mirroring `WebDavClient`'s contract exactly, swapping
   `Credentials.basic(...)` for `Authorization: Bearer <token>` and the
   WebDAV verbs for this server's JSON API. `OneHostInterceptor` is reused
   unchanged.
2. **Login screen** posting to **`xx-chat`'s** `POST /api/v1/fabric/login`
   (not an xxnote-server endpoint — this server never verifies a password,
   only a token) to obtain `{user_id, token, expires_at}`; the app stores
   only the token.
3. **`CredentialVault`/`KeystoreKeyOps`** stay unchanged — they now seal the
   session token instead of a DSM password; `CredentialEntity` gains
   `userId` and drops `basePath` (the server always hands back the one vault
   root).
4. Setup-flow and network-security-config updates to point at the fabric
   backend host instead of a user-typed Synology address.
5. Recommend keeping `WebDavClient` as a selectable alternate profile — the
   DSM-direct path is additive, not replaced.

## 6. Troubleshooting

- **Process exits immediately at startup** → almost always
  `FABRIC_CLUSTER_KEYS_PATH` (or `-keyring`) unset, unreadable, or malformed
  — `main.go` calls `fabric.LoadKeyring` before constructing the vault store
  or binding a port, and `log.Fatalf`s on failure. Check the path and its
  permissions.
- **`401` on every request** → the caller isn't sending
  `Authorization: Bearer <token>`, the token is malformed/expired, or it was
  signed by a key not present in this node's keyring (e.g. after a key
  rotation the old ring wasn't updated). `/healthz` staying `200` while every
  other route is `401` is expected — health is intentionally unauthenticated.
- **Notes "missing" after a restart / wrong data** → confirm whatever started
  the binary used the absolute `-data /srv/deep/xxnote` (or
  `XXNOTE_DATA_DIR`), not a relative path or the install dir — same pitfall
  class as Jal's 2026-08-22 incident (parent `AGENTS.md`).
- **`412` on a write the client didn't expect** → check which conditional
  header was sent: `If-Match` failing means the server's copy has moved on
  (a real conflict — see `SyncEngine`'s conflict handling once the Android
  client lands); `If-None-Match: *` failing means the file already exists
  (the `putIfAbsent` semantics working as designed, not a bug).
- **A request 400s as "invalid path"** → `ResolveUserPath`/`ValidateRel`
  rejected something in the request path (traversal attempt, NUL byte,
  backslash, percent-encoded separator, an over-long segment, or a symlink
  component) — this is the isolation choke point working, not a server
  defect; check what path the client actually sent.

## 7. Part of the Skippy constellation

xxnote-server is one of the fabric add-ons documented in the constellation
map at `/media/Working-Storage/GitHub/Skippy-Project/AGENTS.md`, under the
"xxnote-server add-on" section — keep that file in sync with any change here
to port, health payload shape, data dir, or `FABRIC_CLUSTER_KEYS_PATH`
behavior. This add-on is not yet wired into a Skippy `AddonSpec` — that is a
deliberate, separate deploy step (per this repo's own `server/README.md`).

This repo is part of the Skippy estate — see the
[Skippy manual](https://github.com/PiercingXX/Skippy/blob/main/docs/MANUAL.md)
for the constellation overview and cross-repo contracts.
