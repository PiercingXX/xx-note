# xxnote-server — the fabric backend for xx-note

A single static Go binary (stdlib only) that serves each estate user's notes as
plain `.md` files under `<data>/users/<user_id>/vault/`, authenticated against
the estate fabric identity. It is the server half of moving xx-note off
direct-to-Synology WebDAV and onto the shared fabric login, modeled on
`xx-drive`'s shape and security posture.

The storage philosophy of the app is preserved: a user's notes are still just a
folder of `.md` files on disk (plus `attachments/`), so an operator with shell
access can `cat` them directly — only the transport and auth changed.

## Endpoints

All paths under `/api/v1/` require `Authorization: Bearer <fabric-token>`; a
missing/invalid/expired token is `401`. They mirror the Android app's
`RemoteFiles` port (`app/.../sync/Ports.kt`) 1:1, so the client change is a
single new `RemoteFiles` implementation behind the existing port.

| `RemoteFiles` method | HTTP | Notes |
|---|---|---|
| `list(dir)` | `GET /api/v1/list?dir=<dir>` | JSON `{"entries":[{name,etag,size,dir}]}`; missing vault reads as empty |
| `get(path)` | `GET /api/v1/file/<path>` | raw bytes + `ETag` header, or `404` |
| `getFile(path)` | `GET /api/v1/file/<path>` | same endpoint — text and binary share one path space |
| `put(path, text, ifMatch)` | `PUT /api/v1/file/<path>` + `If-Match: <etag>` | `200` `{"etag":…}` / `412` on mismatch |
| `putIfAbsent(path, text)` | `PUT /api/v1/file/<path>` + `If-None-Match: *` | `200` / `412` if it already exists |
| `putFile(path, bytes)` | `PUT /api/v1/file/<path>` | unconditional; same endpoint |
| `move(from, to, overwrite)` | `POST /api/v1/move` `{from,to,overwrite}` | `204`; `overwrite:false` onto an existing dest → `412` |
| `delete(path)` | `DELETE /api/v1/file/<path>` | `204` / `404` |
| `mkcol(dir)` | `POST /api/v1/mkcol` `{dir}` | idempotent `204` |
| health | `GET /healthz` | `200 ok`, no auth (matches xx-drive / radio) |

`ETag` is the strong content hash (`"<sha256-hex>"`) so `SyncEngine`'s
`If-Match`/412 conflict path works unchanged.

## Auth — fabric token validated locally in Go

The token is a **ClusterKeyring v1** bearer token minted by xx-chat's
`POST /api/v1/fabric/login` (documented in `xx-chat/docs/FABRIC-AUTH.md` and
`skippy-tel-network/docs/FABRIC-AUTH.md`). `internal/fabric` validates it
**locally** — no call back to xx-chat — reproducing the documented format
byte-for-byte:

```
v1.<key_id>.<b64url(claims_json)>.<b64url(hmac_sha256(secret, signed_part))>
signed_part = "v1.<key_id>.<b64url(claims_json)>"
```

Validation (`crypto/hmac`, stdlib): split into 4 dot-separated parts, require
`v1`, look up the signing secret by `key_id` in the cluster keyring, recompute
the HMAC-SHA256 over the exact `signed_part` string, constant-time compare
(`hmac.Equal`), then decode the claims and reject if `now >= exp`. The
`user_id` claim is returned only on full success. This matches the reference
Python validator `skippy-tel-network/syncdaemon/fabric_auth.py` and is pinned to
the Python minter by a golden-vector test (`internal/fabric/token_test.go`,
`TestGoldenVectorFromPythonMinter`) generated from the real
`xx-chat/xxchat/fabric_tokens.py`.

The signing key material is the operator-provisioned **cluster keyring** JSON
(`{"keys": {<id>: <hex>}, "active_key_id": <id>}`), located via
`FABRIC_CLUSTER_KEYS_PATH`. This node is **validate-only** — it never signs.
A missing/unreadable ring is fatal at boot (fail closed).

## Per-user isolation — the choke point

`vault.Store.ResolveUserPath(userID, rel)` is the single path-derivation choke
point (adapted from `xx-drive`'s `fsdrv.ResolveUserPath`). Every handler passes
the `user_id` **from the validated token only** — never a URL param, body
field, or client header — as `userID`, and every disk touch goes through this
one function. It:

1. rejects a `user_id` that is not a safe single segment;
2. lexically cleans and vets the request path (rejects NUL, backslashes,
   percent-encoded separators/dots, over-long segments, absolute paths;
   collapses `..`);
3. joins under `<root>/users/<userID>/vault` and asserts the result stays at or
   below that root;
4. walks the path component-by-component, refusing any symlink component.

### Tests (the deliverable's proof)

- `internal/vault/vault_test.go`
  - `TestTraversalCorpus` — the classic payload corpus (`../`, encoded `%2e%2e`,
    absolute, backslash, sibling-user reach, NUL, over-long) all contained.
  - `TestSymlinkEscape` — a symlink planted inside a vault cannot read outside.
  - `TestUserIDAsPathSegment`, `TestValidateRelBasics`, `TestRoundTripCRUD`.
- `internal/api/server_test.go`
  - `TestTwoUserIsolation` — two real tokens (A and B); A's token can never
    read, overwrite, delete, or move B's sentinel note, across the full
    endpoint matrix **and** at the store level with correctly-counted
    traversal paths that genuinely reach B's vault when containment is absent.
    Verified to go RED when the choke point is disabled.
  - `TestForgedTokenRejected`, `TestExpiredTokenRejected`,
    `TestUnauthenticatedRejected`, `TestNoteLifecycleOverHTTP`, `TestHealthz`.
- `internal/fabric/token_test.go` — golden vector from the real Python minter,
  plus expiry / wrong-key / forged-signature / tampered-claims / malformed /
  header-parsing / ring-load cases.

## Run

```
xxnote-server -addr 127.0.0.1:8746 -data /srv/deep/xxnote
# env: XXNOTE_ADDR, XXNOTE_DATA_DIR, FABRIC_CLUSTER_KEYS_PATH
```

- **Port 8746** (free in the estate `87xx` band; Jal holds 8744).
- **Health**: `GET 127.0.0.1:8746/healthz`.
- **Data dir**: `/srv/deep/xxnote` on the ZFS pool — never a relative path,
  never the install dir.
- `deploy/xxnote-server.service` is the hardened systemd unit.

### Not done here (deploy-time, deliberately out of scope)

Registering the `AddonSpec` in Skippy and updating the umbrella
`Skippy-Project/AGENTS.md` repo table + a new "xx-note add-on" section is a
**deploy** step (per that file's own header rule) and is left for the deploy
lane — this change builds and proves the server but does not deploy it.

## Build & test

Go 1.25, stdlib only, zero external modules.

```
go build ./...   # clean
go vet ./...     # clean
go test ./...    # all green
```
