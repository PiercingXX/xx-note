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
tile / share-to-note stay out.

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

## Housekeeping

- [ ] Retire or rewrite `todo-hardening.md` so it does not contradict P0.4/P0.5.
- [ ] README device claims match what was actually proven.

---

## Stop conditions

- Timestamp conflict resolution anywhere → reject.
- Discarding bytes to look tidy → reject.
- Skipping WS0 and calling sync done → reject.
- Wiring `xxnote-server` without a new decision → reject (option B stands).
- v2 reminders/widget as a drive-by → reject.
