# WS0 Results Worksheet — XX-Note probe kit

Fill this in from the `TRANSCRIPT` blocks printed by
`probe/scripts/ws0-probe.sh`, then transcribe the answers into
`design.md` §4 (replacing the **[VERIFY]** flags) before the sync engine is
written (todo.md rule #1). Throwaway deliverable: once design.md carries the
answers, this file has no further job.

- Date run:
- Operator:
- Host probed (`WS0_HOST:WS0_PORT`):
- Account used (`WS0_USER`): xxnote (D15 service account)

---

## Probe 1 — ETag strength & stability

**Question:** does DSM's `PROPFIND` return a strong, stable `getetag`
(no `W/` weak tags; identical across two back-to-back calls)?

**ANSWER:** strong? YES / NO — stable? YES / NO

**EVIDENCE:** *(paste TRANSCRIPT — PROBE 1)*

```
(paste here)
```

---

## Probe 2 — Is `If-Match` honored on PUT?

**Question:** does a `PUT` with a correct `If-Match` succeed (expect
204/200 — record actual), and a deliberately wrong one fail with 412
(record actual)? Record every status verbatim.

**ANSWER:** create=___ correct-If-Match=___ wrong-If-Match=___ → honored? YES / NO / PARTIAL

**EVIDENCE:** *(paste TRANSCRIPT — PROBE 2)*

```
(paste here)
```

---

## Probe 3 — PROPFIND `getetag` vs GET `ETag`

**Question:** is the etag from `PROPFIND` byte-identical to the `ETag`
response header of `GET` on the same file?

**ANSWER:** MATCH / MISMATCH — values: PROPFIND=`…`, GET=`…`

**EVIDENCE:** *(paste TRANSCRIPT — PROBE 3)*

```
(paste here)
```

---

## Probe 4 — Service-account path discovery (answers O1)

**Question:** which vault path prefix does the dedicated `xxnote` account
actually see over WebDAV (207 vs 404/401 per candidate)?

| Candidate prefix | Status |
|---|---|
| `/home/` | |
| `/home/Drive/` | |
| `/home/Drive/Notes/` | |
| `/Drive/` | |
| `/Notes/` | |
| `/homes/xxnote/Drive/Notes/` | |

**ANSWER:** visible prefixes:

**EVIDENCE:** *(paste TRANSCRIPT — PROBE 4)*

```
(paste here)
```

---

## Probe 5 — Certificate served on :5006

**Question:** does the WebDAV listener serve the Tailscale-issued Let's
Encrypt certificate for the MagicDNS name (subject/issuer/dates/SAN), and
does the chain validate (`-verify_return_error`)?

**ANSWER:** cert subject=… issuer=… valid till=… SAN covers host? YES / NO — chain verifies? YES / NO

**EVIDENCE:** *(paste TRANSCRIPT — PROBE 5)*

```
(paste here)
```

---

## Probe 6 — Trash folder semantics smoke test

**Question:** can we `MKCOL .xxnote/trash`, `MOVE` the probe file into it
(expect 201), see it vanish from the live listing yet remain under trash/,
and clean up with `DELETE`?

**ANSWER:** MKCOLs=___ / ___ MOVE=___ vanished-from-live=YES/NO present-in-trash=YES/NO cleanup DELETEs=___ / ___

**EVIDENCE:** *(paste TRANSCRIPT — PROBE 6)*

```
(paste here)
```

---

## O1 DECISION — My Drive or plain shared folder

*(default per design §18: My Drive, for free server-side version history)*

- [ ] **My Drive** — vault at `/home/Drive/Notes/` (or as discovered); Synology Drive versioning + desktop client apply.
- [ ] **Plain shared folder** — vault at `/Notes/` / `/Drive/...`; simpler path, no Drive coupling; point Drive's own client at it as a synced folder if desired.

Chosen vault path: `______________________`

Ruling recorded into todo.md ("Decisions still open" → settled) and design.md §18 row O1.

---

## ETags-USABLE gate — decides design §4.2's scheme BEFORE the engine is written

ETags are usable iff: **Probe 1** strong AND stable, **AND** **Probe 2**
honors If-Match (2xx on correct, 412 on wrong), **AND** **Probe 3** MATCH.

- [ ] **USABLE** → §4.2's optimistic-concurrency scheme stands as designed; fallback NOT adopted.
- [ ] **NOT USABLE** → adopt §4.2's fallback (size + `getlastmodified` + full-body SHA-256 on read) *before* WS2/WS5 work begins. This narrows the lost-update race instead of closing it and must be stated on the sync screen (design §15).

Gate checked by (initials/date): ________
