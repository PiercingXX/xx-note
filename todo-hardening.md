# XX-Note — Hardening Plan (post WS0–WS10)

> **Status (2026-08-23 hardening pass):** items **2–13, 16, 17 implemented** and
> verified — 585 unit tests / 0 failures / 1 skipped (deliberate), release build
> green, `lintDebug` green, permission + dependency audits green, review-pass S1/S2
> findings fixed (sync-pass mutex + live-pass counter, engine generation guard,
> narrowed permanent-unseal set, singleton test de-vacuumed, sdk-23 audit).
> **Still open, hardware-gated:** #1 probe run + design §4 transcription + O1 +
> conditional §4.2 fallback (needs the real DSM); #5 install/launch on `caiman`;
> #14 *execution* of the new androidTest suites (`KeystoreKeyOpsTest`,
> `MinifiedSmokeTest` — authored and compile-verified only); #15 R3 run.
> Deferred by review: dedicated ConnectionState wording for unseal failure
> (reuses accurate 401 re-entry wording); post-ON_STOP `insertImage` flush window
> (pre-existing); CI action pinning by SHA.

Companion to [todo.md](todo.md) (the build plan, now stale — it and the README
still say "nothing built"). This file is the gap list between "the code exists
and its tests are green" and "this is reliable enough to trust with the only
copy of your writing."

**Verified state as of this review:**

- `./gradlew testDebugUnitTest :core:test` — **567 tests, 0 failures, 1 skipped**
  (`HeicTranscodeRoboTest`). Core: 131. App: 436.
- `./gradlew :app:assembleRelease` — **succeeds**, R8 minification included.
- No `TODO`/`FIXME`/stub markers anywhere in `main` source.
- 13.7k lines main / 10.0k lines test.

The code is in far better shape than the docs suggest. Everything below is a
gap in *verification and lifecycle*, not in logic.

---

## P0 — the guarantee is unproven or breachable

### 1. WS0 was never run. All five `[VERIFY]` flags are still open.

`probe/results-template.md` is blank and `design.md` still carries five
`[VERIFY]` markers (lines 165, 186, 200, 212, 267). WS0's own gate was
"written answers to all six questions pasted into design §4 replacing the
`[VERIFY]` flags" — and todo.md rule #1 opens with **"WS0 is not optional."**
The engine was written anyway.

The whole optimistic-concurrency scheme rests on ETag behaviour that has never
been observed on the operator's actual DSM. Two undetected degradation modes
follow directly:

- **No `getetag` from DSM** → `BaseSnapshot.etag` is `null` → `WebDavClient.put`
  omits `If-Match` entirely (`app/.../net/WebDavClient.kt:116-120`) → every
  push is an **unconditional overwrite**. This is silent last-writer-wins on
  the server, which is precisely what README's headline rule forbids. Nothing
  logs it, nothing warns, no fallback engages.
- **Weak `W/"..."` ETags** → `PropfindParser` preserves them verbatim (by
  design, and correctly) → `If-Match` with a weak tag must fail strong
  comparison per RFC 7232 → every conditional PUT 412s → `decidePushRejection`
  burns three replan rounds → **`Verdict.Fork` on every single edit.**

**Do:**
- [ ] Run `probe/scripts/ws0-probe.sh` against the real NAS. Fill in
      `probe/results-template.md`.
- [ ] Transcribe answers into `design.md` §4, removing all five `[VERIFY]`
      markers. Decide **O1** (Drive My Drive vs. plain shared folder) and
      record it.
- [ ] Add a hard guard regardless of the outcome: if a push is about to go out
      with `ifMatch == null` on a note that **has** a base snapshot, refuse the
      write and surface it on the sync screen. A silent unconditional
      overwrite must be unreachable by construction, not by assumption.
- [ ] Add a weak-ETag detector: if `etag.startsWith("W/")`, flag once at setup
      in plain words rather than discovering it as a fork storm.
- [ ] Write the §4.2 weaker fallback if WS0 says ETags are unusable. Rule #1
      says this is adopted *before* the engine is written; it is now adopted
      after, so it needs a deliberate retrofit rather than a bolt-on.

### 2. The editor can lose the last 800 ms of typing.

`EditorViewModel` saves only through an 800 ms debounce
(`SAVE_DEBOUNCE_MS = 800`, line 466). The timer correctly runs on a private IO
scope that outlives the ViewModel — so back-navigation is safe. But there is
**no `ON_STOP`/`ON_PAUSE` flush and no `onCleared` override anywhere in the
editor.** A process death inside that window (swipe away from Recents, OOM
kill, crash) discards the text.

Every other screen — `GridScreen`, `TrashScreen`, `ArchiveScreen`,
`LabelsScreen`, `LabelGridScreen` — installs a `LifecycleEventObserver`. The
one screen where losing state actually costs the user something does not. That
reads as an omission, not a ruling.

For an app whose thesis is "never lose text," this is the most likely way a
real user actually loses text — far more likely than any sync subtlety.

**Do:**
- [ ] Flush the pending save on `ON_STOP` (cancel the debounce, persist
      immediately). Add a `DisposableEffect` + `LifecycleEventObserver` in
      `EditorScreen`, matching the pattern the other five screens already use.
- [ ] Override `onCleared()` to flush as a second line of defence.
- [ ] Test: type, background the app, kill the process, reopen — text present.

### 3. Background sync never runs. `enqueuePeriodic` is dead code.

`SyncWorker.enqueuePeriodic` is defined (line 118) and **called from nowhere**
— ten call sites reach for `enqueueExpedited`, zero for `enqueuePeriodic`. The
app only ever syncs as a side effect of the user touching the UI.

Design §4.4 specifies a 15-minute periodic pass, and the manifest's merged
`RECEIVE_BOOT_COMPLETED` + `WAKE_LOCK` are justified in a comment as
"WorkManager's boot-persistence mechanism" — for work that is never enqueued.
The app carries a boot permission for a feature that does not exist.

Consequence: edit a note in Obsidian on the desktop, never open the phone app —
the phone never learns. That is not what the sync screen implies.

**Do:**
- [ ] Call `enqueuePeriodic` once after Setup completes, and on app start when
      a credential row exists. There is no `Application` subclass — either add
      one, or call it from `MainActivity.onCreate` next to the existing
      credential check.
- [ ] Then re-justify or drop `RECEIVE_BOOT_COMPLETED`/`WAKE_LOCK` honestly.

### 4. Android Auto Backup would upload the entire vault to Google.

The manifest sets no `android:allowBackup` and no `dataExtractionRules`.
`allowBackup` **defaults to true**. That puts `filesDir/vault` — every note —
plus the Room database containing the sealed credential blob into Google Drive
cloud backup.

README: *"Nothing this app sees goes anywhere but your own NAS."* That claim is
currently false on any device with Play Services. (It happens to hold on the
GrapheneOS target, which has no GMS — but the claim is written unconditionally,
and the fix is one line.)

Second-order: the Keystore key is **not** backed up, so a restore yields a
credential blob that can never be unsealed — a confusing failure with no
message.

**Do:**
- [ ] `android:allowBackup="false"` on `<application>`.
- [ ] Add `android:dataExtractionRules` excluding the vault and DB, for D2D
      transfer on Android 12+.
- [ ] Handle unseal failure explicitly: catch `AEADBadTagException` /
      `KeyPermanentlyInvalidatedException` in `SyncGraph.engine` and route to
      "credentials need re-entering" rather than a null engine reported as
      "not configured."

### 5. The release APK is unsigned — it cannot be installed.

`assembleRelease` produces `app-release-unsigned.apk`. There is no
`signingConfig` in `app/build.gradle.kts`. WS7's gate is "replace Keep for
real, on the real vault" — which requires an installable release build.

**Do:**
- [ ] Add a release `signingConfig` reading from `local.properties` or env
      (keystore path/passwords must never enter git).
- [ ] Build, install on `caiman`, confirm it launches minified.

---

## P1 — real bugs, bounded blast radius

### 6. `SyncGraph.wired` is cached for the process lifetime and never invalidated.

`SyncWorker.kt:173` caches the built `SyncEngine` in a `@Volatile` field. Setup's
`persist()` upserts a new credential row but never clears it, and
`markCredentialStale` does not either. Re-running Setup to correct a wrong host,
or changing the password after a 401, has **no effect until the process dies.**

- [ ] Add `SyncGraph.invalidate()`; call it from `persist()` and from
      `markCredentialStale`.

### 7. Expedited sync chains without bound.

`enqueueExpedited` uses `ExistingWorkPolicy.APPEND_OR_REPLACE`. The M5 comment
explains why it is not `KEEP` — correct reasoning. But every debounced save
appends another full sync pass, and each pass does a whole-vault `PROPFIND` +
per-note reconciliation. A ten-minute writing session queues dozens of
redundant full passes that execute serially.

- [ ] Collapse: keep at most one queued follow-up (a pending-intent flag the
      running pass re-checks on completion), rather than one work request per
      save.

### 8. `runBlocking` database read on the main thread at cold start.

`MainActivity.onCreate:31-33` opens a Room database and blocks the main thread
on a query to pick the start destination. The comment argues it is fast because
it is a PK lookup — but the *first* `.build()` also runs schema creation/open
on a cold, possibly encrypted filesystem. StrictMode will flag it; a slow
device will show it.

- [ ] Render a neutral start state and resolve the route asynchronously, or
      keep a tiny `SharedPreferences` boolean written at Setup.

### 9. Six unclosed Room instances on one database file.

`XxDatabase.builder(...).build()` is called from `MainActivity`,
`SetupViewModel`, `SyncWorker` (×2), `EditorViewModel`, `GridViewModel`, and
`SyncGraph` — each a separate instance with its own connection pool, none
closed. `GridViewModel`'s KDoc says "Room supports concurrent handles," which
is true for one-shot queries and there are **no `Flow` DAOs**, so this is not
currently a correctness bug. It is resource churn, and it becomes a real bug
the moment anyone adds an observable query (invalidation does not propagate
across instances without `enableMultiInstanceInvalidation()`).

- [ ] Make `XxDatabase` a proper process singleton. Cheap now, load-bearing later.

### 10. `proguard-rules.pro` is an admitted stub with minification enabled.

The file says *"Intentionally a stub for WS1. Rules arrive with the workstreams
that add the libraries that need them: Room + FTS (WS3), OkHttp / WebDAV (WS4),
WorkManager sync engine (WS5)."* Those workstreams shipped; the rules never
arrived. R8 currently succeeds on the AARs' bundled consumer rules alone — but
nothing pins that, and no test exercises the minified APK.

- [ ] Either add the rules or replace the stub comment with a statement that
      consumer rules suffice.
- [ ] Add one smoke test against a minified build (Room open, an OkHttp call,
      a WorkManager enqueue).

### 11. `network_security_config.xml` still ships the placeholder domain.

`nas.your-tailnet.ts.net`, with a `⚠ REPLACE AT SETUP (WS6)` banner. Because
the host is runtime-configured, the `<domain-config>` block matches nothing and
the real enforcement is `base-config` (cleartext off, system anchors) plus
`OneHostInterceptor`. So it is inert — but it is misleading dead config sitting
in a file whose whole purpose is being auditable.

- [ ] Delete the `<domain-config>` block and document that the base config plus
      the interceptor carry the claim.

### 12. `atomicWrite` does not fsync the parent directory.

`VaultStore.kt:336-356` does the right things — temp file, `fd.sync()`, atomic
rename — but never fsyncs the directory, so on power loss the rename itself may
not be durable. Minor on modern ext4/f2fs; cheap to close.

- [ ] fsync the parent directory after `Files.move`.

---

## P2 — process gaps that let the above happen

### 13. There is no CI. At all.

No `.github/`. todo.md's standing rules mandate, at **every** workstream exit:
`aapt2 dump permissions` showing exactly four permissions, and a dependency-tree
audit for anything that talks to a network on its own. Neither is automated;
both are the kind of check that rots silently. The merged manifest already
shows seven permission entries — `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, and
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` merged in from WorkManager. The
manifest comments acknowledge the first two; nothing acknowledges the third.

- [ ] CI: build, `testDebugUnitTest`, `:core:test`, `assembleRelease`, lint.
- [ ] CI: assert the merged-manifest permission set against an explicit
      expected list — so the list changes only when someone edits the list.
- [ ] CI: dependency allowlist audit (R8 — one host means one host).

### 14. Zero instrumented tests.

No `androidTest` source set exists. WS4/WS5 gates say instrumented tests "run
against the operator's real DSM over Tailscale," and §16 says the StrongBox
path is proven on hardware. `KeystoreKeyOps` — the class holding the credential
key, including the StrongBox-unavailable fallback — has **never executed.**

- [ ] `androidTest` for `KeystoreKeyOps` on `caiman`: seal/unseal round-trip,
      StrongBox path, TEE fallback, tampered-blob rejection.
- [ ] Instrumented WebDAV suite against the real DSM.

### 15. The R3 test has no recorded run.

"Clear app data, resync, lose nothing" is mandated at every exit from WS5 on,
and is the test of D1 (vault is truth, Room is cache). No evidence it was run
against the real NAS.

- [ ] Run it. Record the result in the repo.

### 16. The docs are ten workstreams stale.

`README.md` says **"Status: specification only. Nothing built."**
`todo.md` says **"Status: nothing built."** Both are wrong; WS0–WS10 are
committed. The manifest also promises components "arriving in later
workstreams" that have since arrived.

- [ ] Update both status lines and the manifest's forward-looking comments.
- [ ] Record **O4** (trash expiry: 7 days or longer) — still listed as open.

### 17. Minor

- [ ] Five `LocalLifecycleOwner` deprecation warnings — move to
      `androidx.lifecycle.compose`.
- [ ] `HeicTranscodeRoboTest` is skipped; confirm that is deliberate and say why.
- [ ] Three unchecked-cast warnings in tests (`NoteDaoTest:107`,
      `VaultStoreTest:450`, `MultiSelectIntentTest:67`).

---

## Suggested order

**Trust the guarantee again:** 1 (WS0 probe + push guard) → 2 (editor flush) →
15 (R3 test).
**Make it installable and honest:** 5 (signing) → 4 (backup) → 3 (periodic
sync) → 16 (docs).
**Stop the rot:** 13 (CI) → 14 (instrumented/StrongBox).
**Then P1 cleanup** — 6 through 12.

Items 1 and 2 are the two places where the app can currently lose text without
telling anyone. Everything else can wait behind them.
