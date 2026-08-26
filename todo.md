# XX-Note — Work Plan

Spec: [design.md](design.md). Target hardware on the desk: Pixel 6 (`oriole`),
GrapheneOS. Spec target remains Pixel 9 Pro (`caiman`), Android 17 / SDK 37.

**Status (2026-08-26):** WS0–WS10 are implemented. 675 JVM tests green
(`app` 540, `core` 135, one deliberate skip). The 2026-08-23 hardening pass
closed items 2–13 and 17 in code — that ledger lives in
[todo-hardening.md](todo-hardening.md) and is not to be re-opened. A ship
review of HEAD `910902b` still said **NO-GO** as the daily-driver of record
whose only copy of writing lives here.

**Update (2026-08-26 session):** P0.1–P0.5 are closed **in code, with
tests** (675 JVM tests green: `app` 540, `core` 135, one deliberate skip;
`assembleRelease`, `lintDebug`, `check-permissions.sh`, `check-deps.sh` all
green). P2.10, P2.12, P2.13, P2.14 closed; P2.11 decided (option B — see
below). A second review pass over the diff found one S1 (fork-path
cancel-without-join) and six S2s; those plus the remaining honesty holes
from a third pass (dirt-under-lock, parked merge-write, import listing
failure, absolute PROPFIND hrefs, capture-file cleanup, canned JPEG tests)
are closed. **The app
is still not good to go**: everything in P1 below needs the Pixel 6 and the
real NAS, and until those run, the headline stays false. Debug builds only,
never the only copy.

**It is not good to go.** Local-only after Setup is GO-WITH-CAVEATS at best
(Setup itself needs a live HTTPS WebDAV host once). Syncing to a real NAS or
`xxnote-server` is NO-GO until proven on hardware: WS0 unrun, R3 unrecorded,
androidTest never executed, no signed install. README's device claims are
still exactly as far as they have been proven.

**Ship gate (this file's job):** close P0, run P1 on `oriole`, then the
headline is allowed to be true. Until then: debug builds only, never the
only copy.

---

## Read this before starting

**1. WS0 is not optional.** The engine leans on five facts that are true of
WebDAV in general and unproven against the operator's actual DSM: whether
`PROPFIND` returns a strong stable `getetag`, whether `PUT` honors
`If-Match`, whether that ETag matches the one `GET` returns, what path the
dedicated `xxnote` account actually sees the vault at, and whether the
Tailscale certificate is served on the WebDAV listener rather than only on
the DSM web UI. Add StrongBox availability for the credential key. A `curl`
script is a legitimate WS0 deliverable. **If ETags turn out to be unusable,
design §4.2's weaker fallback is adopted as a deliberate retrofit — or
Setup fails closed. Silent last-writer-wins is not a third option.**

**2. The failure direction is a law, not a preference.** No sync outcome may
reduce the bytes the user can still read. Deletes go to trash, conflicts
fork into two visible notes, an edit always outranks a delete. If a change
makes any path quieter, tidier, or more decisive at the cost of discarding
somebody's text, it is wrong regardless of how reasonable it looks. The §6
property test exists to catch exactly this and it is not to be relaxed.

**3. There is no timestamp-based conflict resolution, anywhere.** Not as a
fallback, not as a setting, not "just for the frontmatter." Clock skew is
real and `getlastmodified` has one-second resolution. If a code path ever
needs to ask which side is newer in order to decide what to keep, the design
has been violated. Size+mtime+hash in §4.2 is a *lost-update detector*, not
a winner picker.

**4. The vault is truth; Room is a cache and an outbox.** Every feature must
have a file representation before it has a column. The test of it is R3:
wipe the app's data, resync, lose nothing. Run that test by hand and record
it before calling this app trustworthy.

**5. The pure core carries the correctness burden.** `SyncPolicy`, `Diff3`,
`Frontmatter`, `Slug`, `Ulid`, and `ChecklistMerge` import nothing from
`android.*`. Do not route a `type: checklist` body through `Diff3`.

**6. Frontmatter round-trip is byte-exact or it is broken.** XX-Note owns
the keys in design §8 and touches nothing else. An unknown key written by an
Obsidian plugin must survive every rewrite unchanged, and a malformed block
must degrade to body text rather than being discarded.

**7. One host means one host.** The interceptor throws — it does not log,
warn, or fall back. `INTERNET` is declared because it must be; CI fails the
build on permission drift and on a dependency that talks to a network of
its own.

**8. Checklists do not merge by lines.** D18/D19/O3. If a change ever routes
a checklist body through `Diff3`, the ruling has been silently undone.

### Decisions still open

- **O1** — vault inside Synology Drive's My Drive (free server-side version
  history) or a plain shared folder (simpler path, no Drive coupling).
  **WS0 answers this.** It is still unanswered because WS0 has not been run
  against the real NAS.

**Settled. Do not re-open:**

- **O2** — the brand palette stands. Keep colour names round-trip; they
  render as six AMOLED tones. Design D12.
- **O3** — checked items rewrite the file on a user edit here, never on
  pull. Item-wise checklist merge. Design D18, D19, §7.1.
- **O4** — trash expiry is Keep's 7 days as a constant
  (`TrashMath.EXPIRY_DAYS`). Nothing exposes a setting. That is the
  shipped half of design §18; the "configurable" half did not ship.

---

## Done — do not re-open

WS0–WS10 produced the app. The 2026-08-23 pass then closed the lifecycle
and process gaps that were blocking trust *in code*, without proving them
on a phone.

| WS | What shipped | Gate that is still unpaid |
|---|---|---|
| 0 | Probe kit (`probe/scripts/ws0-probe.sh`, results template, throwaway APK) | Written answers in design §4. Template is blank. Five `[VERIFY]` flags remain. |
| 1 | Skeleton, brand, fonts, icon, network security config | — |
| 2 | `core/` — Frontmatter, Diff3, ChecklistMerge, Slug, Ulid, SyncPolicy, Verdict; §6 table + property test | — |
| 3 | Room + FTS, `filesDir/vault` with temp-then-rename + dir fsync, VaultStore, AttachmentStore | — |
| 4 | WebDavClient, PropfindParser, OneHostInterceptor, CredentialVault | App-side must still match a real DSM transcript. |
| 5 | SyncEngine, outbox, MergeEngine, ConflictNamer, WorkManager (expedited + 15-min periodic), vault-safety stop | Headline never run headless against Obsidian + NAS. R3 never recorded. |
| 6 | Setup — host/port, account, test, PROPFIND-browse, confirm, device name, first sync | Import pass is real; on-device confirmation rides R3. |
| 7 | Grid, capture bar, editor, tones | Daily-driver gate unpaid until P1 (device proof). |
| 8 | Labels, archive, trash, FTS, multi-select, drawer, checklist reorder | — |
| 9 | Sync screen, outbox reasons, Resolve, log, Test connection | — |
| 10 | Gallery insert, SHA-256 attachments, upload-before-body, lazy download, cache/orphan, EXIF strip, HEIC→JPEG, CAMERA capture | — |

Hardening closed in code (see [todo-hardening.md](todo-hardening.md) for the
original write-up): ON_STOP / `onCleared` flush (#2, closed under P0.2);
periodic sync enqueued from MainActivity + Setup (#3); `allowBackup="false"`
+ data-extraction rules + unseal failure wording (#4); `SyncGraph.invalidate`
(#6); at-most-one follow-up gate (#7); async start-route (#8); Room process
singleton (#9); consumer-rules statement in `proguard-rules.pro` (#10);
placeholder domain removed from network-security config (#11); parent-dir
fsync (#12); CI on unit tests, release assemble, lint, permission +
dependency audits (#13); LocalLifecycleOwner moved to
`androidx.lifecycle.compose` (#17). Signing *config* exists (#5) and waits
on secrets. androidTest suites are authored (#14) and have never run.

---

## Remaining — P0 close the guarantee

**CLOSED IN CODE 2026-08-26** — all five below are implemented and
JVM-tested (see the per-item ticks for what landed). What remains of P0 is
proving them on hardware, which is P1's job. Original text kept for the
record.

These five are how the app can lose text, or claim a thing it does not do.
They blocked ship. Order is the order to do them: local loss first (no NAS
required), then the sync honesty holes, then the probe that tells you which
fallback is real.

### 1. Editor reseeds from first-load text after recreation

`EditorScreen.kt:189` — after `state.ready` becomes true, `LaunchedEffect(Unit)`
copies `state.initialTitle` / `state.initialBody` into the `remember`ed
`TextFieldValue`s. Those initials are set once in `EditorViewModel.load()`
(`EditorViewModel.kt:180-181`) and are never updated on save. `MainActivity`
does not set `android:configChanges`, so rotation / density / theme
recreation rebuilds composition while the `AndroidViewModel` survives. The
fields snap back to the first-load text. `BasicTextField.onValueChange` does
not fire for that programmatic set, so the VM still holds the real buffer;
the next keystroke sends the stale UI string into `onTitleChange` /
`onBodyChange` and the debounced save overwrites the real note.

`UiState` documents the intended contract as “the screen mirrors them into
TextFieldValue once” (`EditorViewModel.kt:65`). The contract is not held
across recreation.

**Do:**
- [x] Seed from the ViewModel’s *current* title/body (or `rememberSaveable`
      keyed on `noteId`). — DONE: `UiState.generation` keys the seeding
      effect; typing refreshes the initials without bumping the generation,
      so a recreated composition re-seeds from what the user sees.
- [x] Run the seed only on the false→true `ready` transition or when
      `noteId` changes — never on every new composition of an already-loaded
      editor. — DONE: effect keyed on generation, which moves only on
      load/adopt/merge.
- [x] Test: type, rotate, type again, reopen. The file contains both edits,
      not a derivative of the original load. — `EditorResyncTest`,
      `EditorViewModelTest`.

### 2. Dirty flag is cleared before the write lands

`EditorViewModel.kt:405` — `scheduleSave` sets `hasPendingSave = false`
after the 800 ms delay and only then calls `persistNow()`.
`flushPendingSave` (`EditorViewModel.kt:427`) is a no-op when that flag is
false. `persistNow` on `IOException` (`EditorViewModel.kt:484-487`) returns
without restoring the flag.

Two loss windows: (1) process death between the flag clear and
`VaultStore.atomicWrite` completing; (2) a failed write (“not saved · …”)
plus ON_STOP / swipe-away, which will not retry. The flush also launches
persist on `ioScope` without joining, so ON_STOP does not wait until the
bytes are durable. Hardening #2’s flush exists and its unit tests pass; it
does not close the “never lose text” window.

**Do:**
- [x] Keep `hasPendingSave` true until `store.write` returns. On failure,
      leave it true so ON_STOP retries. — DONE: the flag retires only in
      `persistNow`'s success path, after `VaultStore.write` returns (H3's
      trashed refusal is the one deliberate terminal case).
- [x] Join the IO persist (or write on a blocking `ON_STOP` path) before
      returning from the lifecycle callback. — DONE: `flushPendingSave`
      blocks on a cancel-and-JOIN under `flushLock`, then persists only if
      dirt survived the join.
- [x] Test: type, fail the write, background, reopen — text present. Type,
      kill the process inside the persist window, reopen — text present. —
      fail-once retry + latch-proven mid-flight join tests in
      `EditorViewModelTest`.

### 3. Open editor vs a background pull

`EditorViewModel.kt:154` — `load(id)` is the only time the editor reads the
vault. There is no `ON_RESUME` reload (every other screen refreshes on
resume; the editor only flushes on `ON_STOP`). A background `SyncEngine`
pull (`SyncEngine.kt:620-628`) overwrites the mirror and advances the base
while the editor still holds the pre-pull buffer with `hasPendingSave ==
false`. The next keystroke dirties that stale buffer and `persistNow`
writes it through; the following sync sees local-dirty / remote-clean and
PUTs, discarding the pulled Obsidian edit.

Design §15’s “If-Match then replan” defense does not apply: the pull
already moved the base to the remote bytes.

**Do:**
- [x] On resume, and after a completed sync, re-read the file. — DONE:
      ON_RESUME drives `resyncFromDisk` (no sync-completion signal existed;
      resume covers the background-pull case — noted as a possible follow-up
      hook if one is ever added).
- [x] If the buffer is clean, adopt disk. — DONE, atomically: the dirt
      re-check, debounce retirement, and buffer swap share one lock hold, so
      a keystroke cannot slip between "clean" and "adopted".
- [x] If dirty and disk moved, three-way merge against the load-time
      snapshot or fork. Do not save the stale buffer as a fresh edit. — DONE:
      `MergeEngine.merge(base = resync snapshot)`, plain bodies via Diff3,
      checklist bodies item-wise only (mixed-type corners now FORK — see
      deferred-notes), unmergeable → conflict-stamped fork; a mid-flight
      persist in the fork path is joined and the pulled bytes restored to
      disk before adoption.
- [x] Test: open a note, pull a remote edit under it with the buffer clean,
      confirm the editor shows the remote bytes. Same with a dirty buffer:
      both sides remain readable. — `EditorResyncTest` (adopt, plain merge,
      checklist item-wise proof, prose fork, inversion-with-mid-flight-
      persist, double-fork-failure retry).

### 4. Null ETag is an unconditional PUT. The fallback is a lie.

`WebDavClient.kt:118` — `put(..., ifMatch)` omits `If-Match` when the etag
is null. `SyncEngine.pushWrite` / `putConditional` (`SyncEngine.kt:883-888`)
pass `base.etag` through even when a `BaseSnapshot` exists with
`etag == null`. That is silent last-writer-wins on the server — the shape
R6 and the README forbid.

Setup detects “fallback” mode and claims “sync falls back to size+mtime+hash”
(`SetupLogic.kt:283`), stores `KEY_ETAG_MODE`, and **never reads it again**.
No size/mtime/hash compare exists in the engine. Extra holes on the same
path:

- `etagModeOf` (`SetupLogic.kt:275-276`) classifies an empty folder as
  fallback, so a first-run empty vault always gets the lie.
- A non-null `W/"..."` etag is treated as strong. `PropfindParser` keeps
  weakness verbatim (correct); `If-Match` strong-compare then 412s into a
  row-12 fork storm.

**Do:**
- [x] Refuse a push when a base snapshot exists and `etag` is null or
      starts with `W/`. Surface it on the sync screen. A silent
      unconditional overwrite must be unreachable by construction. — DONE:
      every base-derived body write funnels through `guardedPut`; ETAG mode
      + null/weak → `PutResult.Refused` surfaced as a sync-log entry. (In
      FALLBACK mode the write is preceded by the §4.2 verify GET — that is
      the disclosed narrowing, not an unconditional PUT.)
- [x] Detect weakness at Setup confirm, in plain words, rather than as a
      fork storm. — DONE: weak tags classify the server FALLBACK and say so
      in words at confirm.
- [x] Stop classifying an empty listing as fallback. — DONE:
      `etagModeOf(emptyList())` is ETAG.
- [x] Either implement the real §4.2 size+mtime+hash detector (a lost-update
      *detector*, not a newest-wins picker — rule 3) and actually read
      `KEY_ETAG_MODE`, or fail Setup closed when ETags are unusable. — DONE,
      implemented and read: `EtagMode` plumbs the stored setting into engine
      construction (absent/unknown → FALLBACK, the never-blind direction).
      The shipped detector verifies full-body SHA-256 against the base
      snapshot's recorded text before EVERY write — strictly stronger than
      size+mtime pre-checks, same lost-update-detector semantics; remote
      moved → row-12 fork, never overwrite. design.md §4.2's prose still
      says "size + getlastmodified"; reconciling that sentence is a WS0-day
      decision (noted below).
- [x] Tests: push with a null-etag base is refused; weak-tag base is
      refused or handled by the fallback; empty folder does not select
      fallback. — `SyncEngineTest`, `SetupLogicTest`, `SyncGraphTest`,
      `core/EtagTest`.

### 5. Setup promises to import id-less Markdown, then skips it

`SyncEngine.kt:315` — remote `.md` files are matched only when
`Frontmatter.id` is a canonical ULID; anything else is logged as “left
unsynced” and dropped. Setup’s confirm step counts those files
(`SetupLogic.kt:250`, `SetupScreen.kt:100-106`) and tells the user they
“will be imported, ids assigned where missing, nothing will be overwritten”
(`SetupLogic.kt:242`). First sync is a plain `SyncEngine.syncOnce()`
(`SetupViewModel.kt:314-321`) with no import pass.

Ordinary Obsidian/Markdown files have no `id:` ULID, so a pointed-at
existing vault stays on the NAS and never appears on the phone.
`VaultStore.scan` will assign ids only to files already on the local
mirror, which this path never writes.

**Do:**
- [x] Do the disclosed import: GET id-less files, stamp a ULID via
      `Frontmatter.rewritten` (unknown keys survive), PUT under `If-Match`
      or create-only, then pull. — DONE: `sync/ImportPass` runs in Setup's
      first sync before the pull; stamps conditionally under If-Match with
      the listing ETag; 412 → skipped and flagged, never overwritten; a
      missing/weak tag is refused without any request. "Id-less" now means
      no USABLE id (absent, blank, or non-canonical), the same predicate the
      pass stamps by — so the confirm count is exactly the rewrite set.
- [x] Until that exists, the confirm copy must not claim import happens. —
      MOOT: the import exists; every disclosure sentence was audited against
      engine behavior and now tells the truth.
- [x] Test: a remote folder of id-less `.md` files becomes a local vault of
      the same bodies with stable ULIDs; a second sync is a no-op, not a
      duplicate set. — `ImportPassTest`.

---

## Remaining — P1 prove it on a phone

Code being green is not the gate. These have no substitute on a JVM.

### 6. Run WS0 against the real DSM

`probe/results-template.md` is blank. `design.md` still carries five
`[VERIFY]` markers (lines 165, 186, 200, 212, 267). Rule 1 was not waived
by writing the engine first.

**Do:**
- [ ] Run `probe/scripts/ws0-probe.sh` against the real NAS. Fill in
      `probe/results-template.md`.
- [ ] Transcribe answers into `design.md` §4, removing every `[VERIFY]`.
- [ ] Decide **O1** (Drive My Drive vs. plain shared folder) and record it.
- [ ] Confirm StrongBox on `oriole` (and later `caiman`):
      `setIsStrongBoxBacked(true)` for AES-GCM, or document the TEE
      fallback as the device fact.
- [ ] Pick the P0.4 branch from the transcript: real ETags, or the §4.2
      retrofit, or Setup fails closed. Not “we’ll see at runtime.”

### 7. Signed minified APK, installed

`app/build.gradle.kts:74` — release signing attaches only when all four
keystore secrets resolve (`local.properties` or env; never git). Otherwise
`assembleRelease` is `app-release-unsigned.apk`. CI has no secrets, so the
artifact it produces cannot be installed. A debug build already launches on
the Pixel 6. The minified APK that would be the daily-driver binary has
not.

**Do:**
- [ ] Operator-local keys via the existing `local.properties` contract.
- [ ] `assembleRelease`, install on `oriole`, confirm it launches minified.
- [ ] Until then, do not call an unsigned artifact a release.

### 8. Execute the instrumented suites on hardware

`KeystoreKeyOpsTest` and `MinifiedSmokeTest` are authored and compile.
Nothing in `androidTest` has ever run. The class holding the credential
key, including the StrongBox-unavailable fallback, has still never
executed. Authored is not run.

**Do:**
- [ ] `connectedReleaseAndroidTest` on `oriole`: seal/unseal round-trip,
      StrongBox or TEE fallback, tampered-blob rejection, Room open, OkHttp
      transport rules, WorkManager reflective worker instantiation.
- [ ] Instrumented WebDAV suite against the real DSM over Tailscale. There
      is no emulator for a Synology.

### 9. Record the R3 run

“Clear app data, resync, lose nothing” is mandated at every exit from WS5
on, and is the test of D1. No evidence it was run against the real NAS.

**Do:**
- [ ] Point the app at a known vault. Note the file set.
- [ ] Clear app data. Complete Setup. Sync.
- [ ] Diff against the known vault. Anything missing is a release blocker.
- [ ] Paste the result into this file (or `probe/`) so the next person is
      not asked to believe a comment.

Also unpaid from WS5’s own gate, and the same session can cover it:

- [ ] Edit the same note on the phone and in Obsidian between syncs. Get a
      clean merge or two visible notes — never one silently overwritten
      note.

---

## Remaining — P2 daily-driver shape

Not text-loss. Still between “it works on a handful of notes” and “replace
Keep on the real vault.”

### 10. Stop GET-ing the whole vault on a timer. Say so about subfolders.

`SyncEngine.kt:309` — every pass `GET`s every live `.md` in the vault root,
with no short-circuit on an unchanged listing etag. Combined with a
15-minute `PeriodicWorkRequest` (`SyncScheduler.kt:29`) this is a full-vault
download on a timer.

Listing is Depth:1 only (`WebDavClient.kt:88`). Notes in subfolders — a
normal Obsidian layout — are invisible to sync even after P0.5.

**Do:**
- [x] Skip GET when the listing etag matches `base.etag` and local is clean.
      — DONE (2026-08-26): ETAG mode only, requires strong tags both sides,
      mirror bytes == base body, and no pending outbox op. FALLBACK mode
      never skips — the deciding GET is the honest cost of the weaker mode.
- [x] If nested vaults are in scope, walk collections. If they are not, say
      so in Setup when the share has subfolders. — DECIDED: nested vaults
      are not in scope; Setup confirm says "this folder has subfolders —
      files inside them stay on the server" when the listing has any.

### 11. `xxnote-server` is a real binary the app cannot use

`server/docs/ANDROID-FABRIC-CLIENT.md:3` — the Go service has token
isolation, a path choke point, conditional PUT, and tests. Production
`SyncGraph.engine` always builds `WebDavClient` (`SyncWorker.kt:374-380`).
`FabricFilesClient` exists only as uncompiled markdown. The process listens
cleartext on `127.0.0.1:8746` (`server/deploy/xxnote-server.service:15`),
which is fine behind a local reverse proxy; there is no app-side TLS client
for this origin.

**Do:**
- [ ] Either ship a `RemoteFiles` implementation + fabric-login Setup step
      (the port is already there; `SyncEngine` should not change), **or**
- [x] Keep `server/` out of the daily-driver claim. README already says
      “unwired”; do not treat it as a second sync backend until it is
      wired and run from a device. — DECIDED 2026-08-26: option B. Audited:
      README, `server/MANUAL.md`, and `ANDROID-FABRIC-CLIENT.md` all say
      documented-not-built; no overclaim existed, none needed adding. The
      fabric backend becomes product work only if/when it is actually going
      to ship.

### 12. CAMERA capture

Gallery insert shipped. `TakePicture` did not. The permission is declared;
the prompt flow in design §13 is not. Documented as a v1 follow-up in
`EditorViewModel.insertImage` and `EditorScreen`.

**Do:**
- [x] First-capture CAMERA prompt, then `TakePicture` into the same insert
      pipeline as the picker (hash, EXIF strip, HEIC→JPEG, upload-before-body).
      — DONE (2026-08-26): §13 timing (prompt only on first capture tap),
      FileProvider cache URI, captured bytes feed `insertImageBytes`.
- [x] App remains fully usable when the permission is denied. — DONE: one
      line of words, gallery path untouched, no nag loop.
      (`CameraCaptureRoboTest`.)

### 13. Docs that still lie

- [x] `design.md:15` still says “specification only. Nothing built.”
      Replace with the same honest status README carries (built, JVM-tested,
      device-unproven) until P1 lands, then update again. — DONE
      (2026-08-26): status line now says built and JVM-tested,
      device-unproven.
- [x] After P0.4 / P0.5 / P1.6, the confirm copy, etag line, and import
      disclosure must match the engine. — DONE for the code side: the
      fallback line now describes the shipped GET-then-hash verify-then-write
      (the old "size+mtime+hash" sentence was false), import disclosure
      audited true. The §4.2 spec-prose reconciliation is parked with WS0.
- [x] Cut the multi-page class KDocs on `SyncEngine`, `EditorViewModel`,
      `VaultStore`, `SyncWorker` to the non-obvious invariant. History
      belongs in the spec, not above the functions. (`SyncEngine.kt:16` and
      siblings.) — DONE (2026-08-26): class KDocs cut to invariants only
      (SyncEngine 113→27 lines, EditorViewModel 39→21, VaultStore 19→16,
      SyncWorker 25→17).

### 14. CI action pinning

`.github/workflows/ci.yml` references actions by tag, not SHA. Deferred
by the 2026-08-23 review; still deferred. Pin when convenient, not as a
ship blocker.

- [x] Pin `actions/checkout`, `gradle/actions/wrapper-validation`,
      `actions/setup-java` by commit SHA. — DONE (2026-08-26): pinned to
      `checkout@11d5960a… # v4.4.0`, `wrapper-validation@748248dd… # v4.4.4`,
      `setup-java@cf277c60… # v4.9.1`, resolved via `git ls-remote`.

---

## v2 — not this plan

Reminders, home-screen widget, quick-settings tile, and share-to-note.
`reminder:` is already reserved in frontmatter so today’s vaults stay
compatible. Collaboration, drawings, rich text, and cloud accounts are
permanent non-goals, with reasons, in [design.md §2](design.md).

---

## Order

**Close the local-loss windows (no NAS required):** 1 → 2 → 3.

**Make sync honest:** 4 (refuse the unconditional PUT, then the real
fallback or fail closed) → 5 (do the import, or stop claiming it).

**Then ask the hardware:** 6 (WS0 + O1) informs 4’s fallback branch if it
is still open → 7 (signed install) → 8 (androidTest) → 9 (R3 + the
Obsidian round-trip).

**Then the daily-driver shape:** 10 → 12 → 11 only if the fabric backend
is actually going to be a product path → 13 as you touch the files → 14
whenever.

Do not start 10–14 while 1–5 are open. A faster listing does not make a
lost paragraph come back.

---

## Deferred follow-ups (2026-08-26 review pass)

Found by the second review over the P0/P2 diff. Closed in the 2026-08-26
re-review pass (this commit) unless marked accepted.

- **FALLBACK TOCTOU is accepted, in writing.** The §4.2 verify-then-write
  narrows the lost-update race; it does not close it (a remote write landing
  between the verify GET and the PUT still forks on the next pass, never
  overwrites). design.md §4.2 sanctions exactly this. The SyncEngine KDoc
  now says "no *unverified* PUT", which is the true invariant.
- **Merged-write failure corner:** CLOSED — a failed merge write now parks
  the pulled bytes as their own conflict-stamped note before re-arming the
  local pipeline, so a later push cannot discard the Obsidian side.
- **Absolute-href PROPFIND responses:** CLOSED — `PropfindParser` compares
  path only, stripping scheme+authority.
- **Etag reuse after server-side restore:** the GET short-circuit trusts RFC
  7232 strong comparison. A sloppy server that restores a file with a
  byte-identical etag but different bytes would be skipped. Accepted until
  WS0 says what DSM actually does.
- **ETAG-mode refusals can wedge pushes** on a server that answers strong
  listing ETags but omits ETags on PUT responses: every base records null →
  permanent Refused until Setup re-runs. Loud and lossless; consider
  auto-reclassifying to FALLBACK after N refusals. Accepted until WS0.
- **ImportPass listing IOException:** CLOSED — `Report.listingFailed` speaks
  in Setup's first-sync lines instead of masquerading as an empty folder.
- **Cancelled captures orphan `cache/camera/` files:** CLOSED — the capture
  URI is deleted after the bytes are read, and on cancel.
- **`CameraCaptureRoboTest` JPEG encode skip:** CLOSED — tests use a
  hardcoded 1×1 JPEG, so coverage no longer depends on Robolectric's encoder.
- **Sync-completion hook for the editor:** ON_RESUME covers the background-
  pull case; if a sync-finished signal ever exists, hooking it would narrow
  the window further.
- **Dirt flag vs adopt race:** CLOSED — every buffer mutation raises
  `hasPendingSave` under the same lock hold as the field write, so a
  resume-adopt cannot slip between "typed" and "dirty".

---

## Standing checks (every exit)

- A workstream with failing tests is not done.
- `./gradlew testDebugUnitTest :core:test` stays green.
- `./gradlew :app:assembleRelease` stays green.
- `scripts/check-permissions.sh` and `scripts/check-deps.sh` stay green
  (CI already runs both).
- After P1.9: the R3 diff is in the repo. Anything lost is a release
  blocker, not a bug report.

---

## Ship checklist

The app is good to go when every box below is true, in writing, against a
device:

- [x] P0.1–P0.5 closed, with tests. — code side done 2026-08-26 (675 JVM
      tests green); device proof is P1's job.
- [ ] WS0 transcribed; no `[VERIFY]` left in `design.md`; O1 decided.
- [x] Unconditional PUT is unreachable; weak ETags do not fork-storm; the
      fallback either exists or Setup refused. — the fallback EXISTS
      (verify-then-write), refusals are compiler-forced at every write site,
      weakness is disclosed at Setup.
- [x] An id-less Markdown folder pointed at in Setup becomes a vault of
      the same notes. — `ImportPass` + tests; on-device confirmation rides
      R3.
- [ ] Signed minified APK installed on `oriole`.
- [ ] `KeystoreKeyOpsTest` and `MinifiedSmokeTest` have a recorded run.
- [ ] R3 recorded: clear app data, resync, diff, nothing lost.
- [ ] Phone + Obsidian concurrent edit: merge or two notes, never one
      silently overwritten note.
- [x] `design.md` status line matches reality. — built and JVM-tested,
      device-unproven.
- [ ] README no longer has to say “only the setup screen is proven.”
