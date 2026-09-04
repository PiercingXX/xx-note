# XX-Note — Remaining work

**2026-09-04.** WS0–WS10 and P0.1–P0.5 are **code**. The remaining work is
the real NAS + Pixel. Debug builds only until P1 is recorded. Never the
only copy of writing.

Package: `com.piercingxx.xxnote`  
Markdown on your WebDAV vault. Three-way sync. Vault is truth; Room is
cache/outbox. No timestamp winner-picking.

```
Status: 675 JVM tests, setup wizard, sync engine, theme sync. WS0 probe
results blank. androidTest never executed. R3 wipe-resync unrecorded.
```

---

## Locked (do not reopen)

O2 palette, O3 checklist merge, O4 trash expiry, P2.10 nested vaults out
of scope, P2.11 `xxnote-server` unwired (option B). v2 reminders / widget /
tile stay out. **Share-to-note is in-scope** (P2 below).

**Still open until WS0 runs:** O1 (Synology Drive “My Drive” vs plain
shared folder) and the ETag story (§4.2 fallback vs fail-closed).

---

## P1 — ship gate (hardware)

- [ ] Run `probe/scripts/ws0-probe.sh` against the real DSM / Tailscale.
  Fill `probe/results-template.md`.
- [ ] Transcribe into `design.md` §4. Remove `[VERIFY]`. Decide **O1**.
- [ ] If ETags are unusable: adopt §4.2 **or** Setup fail-closed. Silent
  last-writer-wins is not a third option.
- [ ] Operator-local signed / minified APK on the Pixel. Launch under R8.
- [ ] `connectedReleaseAndroidTest`: Keystore seal/unseal, StrongBox or
  documented TEE fallback, tamper rejection.
- [ ] **R3:** clear app data → resync → diff the vault. Record the result.
- [ ] Phone + Obsidian concurrent edit: merge or fork, never silent overwrite.
- [ ] Instrumented WebDAV against the real NAS (no emulator substitute).

**Accept:** this file dates each line. Headline may then say trustworthy.
Until then: debug only, never the only copy.

---

## P2 — Share-to-note

One share target. Keep / Obsidian users will hit this.

- [ ] `ACTION_SEND` / `SEND_MULTIPLE` (`text/plain`, `text/*`) creates a
  new `.md` in the vault (title from subject or first line, body from
  the extra). Attachments that are files become vault files + a link,
  or refuse honestly if the type is not text.
- [ ] Dirty + sync like any other new note. No silent last-writer-wins.
- [ ] If Setup is unfinished, say so and offer Setup — do not write
  into a missing vault.
- **Accept:** share a URL and a selected paragraph from a browser; both
  become notes you can open after a sync.

---

## One door / hub (estate — 2026-09-04)

Locked with `skippy-tel-network/todo.md` WS0. Vault can stay Synology
(or any WebDAV). Estate tel hostname is **one** valid origin. Do not
replace O1/WS0 NAS probe with an estate-only lock-in.

- [ ] Connection / Setup: user types domain + user/pass (Synology,
      estate tel `/note/`, or any WebDAV). No `:845x` / `:8448` as the
      default path.
  - verify: setup fixture accepts an arbitrary HTTPS origin; default
    templates do not list those Tailscale ports
- [ ] If xx-drive hub is up on `127.0.0.1`, use that session for the
      estate origin (no second host typed). Hub down → stored origin +
      creds. No crash loop.
  - verify: hub-present / hub-absent JVM tests; unsigned discovery
    ignored
- [ ] Fabric `user_id` when the origin is the estate door. Margaret
      does not mint. Isolation: user B cannot read user A.
  - verify: existing isolation tests stay fail-closed

**Stop:** estate-only (forbidding Synology); resurrecting per-app
Tailscale ports; treating Margaret as the IdP.

---

## Housekeeping

- [ ] Retire or rewrite `todo-hardening.md` so it does not contradict P0.4/P0.5.
- [ ] README device claims match what was actually proven.

---

## Stop conditions

- Timestamp conflict resolution anywhere → reject.
- Discarding bytes to look tidy → reject.
- Skipping WS0 and calling sync done → reject.
- Wiring `xxnote-server` without a new decision → reject (option B stands).
- v2 reminders/widget/tile as a drive-by → reject.
