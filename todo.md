# XX-Note — Build Plan

Spec: [design.md](design.md). Target: Pixel 9 Pro (`caiman`), GrapheneOS,
Android 17 / SDK 37.

**Status: WS0–WS10 implemented.** 623 unit tests green across `app` (492) and
`core` (131), with one deliberate skip; the release build compiles unsigned
pending signing keys. The gap
between "tests are green" and "trustworthy" lives in
[todo-hardening.md](todo-hardening.md).

---

## Read this before starting

**1. WS0 is not optional.** The sync engine leans on five facts that are
true of WebDAV in general and unproven against the operator's actual DSM:
whether `PROPFIND` returns a strong stable `getetag`, whether `PUT` honors
`If-Match`, whether that ETag matches the one `GET` returns, what path the
dedicated `xxnote` account actually sees the vault at, and whether the
Tailscale certificate is served on the WebDAV listener rather than only on
the DSM web UI. Add StrongBox availability for the credential key. A `curl`
script is a legitimate WS0 deliverable — this probe does not need to be an
APK. **If ETags turn out to be unusable, design §4.2's weaker fallback is
adopted before the engine is written, not bolted on after.**

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
has been violated.

**4. The vault is truth; Room is a cache and an outbox.** Every feature must
have a file representation before it has a column. The test of it is R3:
wipe the app's data, resync, lose nothing. Run that test by hand at every
workstream exit from WS5 on.

**5. The pure core carries the correctness burden.** `SyncPolicy`, `Diff3`,
`Frontmatter`, `Slug`, and `Ulid` import nothing from `android.*` and are
fully testable on the JVM. WS2 finishes — every §6 row named and green, plus
the property test — before any Android code consumes a verdict. Same
discipline as XX-Dialer WS2 and Nope-Mode WS3, for the same reason.

**6. Frontmatter round-trip is byte-exact or it is broken.** XX-Note owns
the keys in design §8 and touches nothing else. An unknown key written by an
Obsidian plugin must survive every rewrite unchanged, and a malformed block
must degrade to body text rather than being discarded. A parser that eats a
note fails rule 2.

**7. One host means one host.** The interceptor throws — it does not log,
warn, or fall back. Its test suite includes the same host on a different
port, a redirect to a third party, and an IP literal. `INTERNET` is the only
network permission and the manifest stays short; the short list is the
claim.

**8. Checklists do not merge by lines.** D18 makes a checklist reorder itself
on save, which makes line-based diff3 structurally wrong for it: two people
ticking different items both produce "a line vanished from the middle, a line
appeared at the end," and the two appends collide. `type: checklist` takes the
item-wise path in design §7.1, where a checkbox **can never conflict**. If a
change ever routes a checklist body through `Diff3`, the ruling has been
silently undone and concurrent tapping starts forking notes.

### Decisions still open

One remains; neither it nor any decision below blocks WS0–2:

- **O1** — vault inside Synology Drive's My Drive (free server-side version
  history) or a plain shared folder (simpler path, no Drive coupling).
  **WS0 answers this**, since it depends on what the service account sees.
  Note WS0 has not been run against the real NAS yet (todo-hardening #1).

**Settled by operator ruling, recorded here so they are not re-opened:**

- **O2 — the brand palette stands.** Keep's twelve colours render as six
  AMOLED surface tones; the Keep colour names still round-trip in frontmatter
  so an imported vault survives. Design D12.
- **O3 — checked items rewrite the file.** Sorting to the bottom of their own
  list block, once per debounced save, never on pull. This forces the
  item-wise checklist merge (design D18, D19, §7.1) — which makes concurrent
  tapping *safer* than the display-only alternative, because a three-way
  boolean has no conflicting case. **WS2 owns it, not WS8.**

**Recorded from the spec's standing default, not an operator ruling:**

- **O4 — trash expiry is Keep's 7 days.** design §18 lists "7 days,
  configurable" as the default-until-overruled and D9 adopts Keep's rule
  verbatim — "trash expires at 7 days … expiry is the only path to a real
  unlink." No ruling ever overruled it, so the default stands. The code ships
  the 7-day half as a constant (`TrashMath.EXPIRY_DAYS`, `EXPIRY_MS`) with a
  days-remaining chip on the trash screen; the "configurable" half of §18's
  wording did not ship — nothing exposes an expiry setting.

---

## Workstreams

| WS | Scope | Gate / exit criterion |
|---|---|---|
| 0 | **Probe** (throwaway; `curl` is fine): `PROPFIND` a test folder and read `getetag`; `PUT` with a correct and an incorrect `If-Match` and record both statuses; compare `PROPFIND` `getetag` to the `GET` `ETag` header; log in as the `xxnote` service account and record the vault's actual path; verify the certificate served on :5006; a 20-line app that seals a byte array with a StrongBox AES-GCM key on `caiman` | Written answers to all six questions pasted into design §4 replacing the [VERIFY] flags, and O1 decided |
| 1 | Skeleton — gradle, manifest (§13), packages (§14), brand tokens vendored as a Compose theme, shipped fonts, launcher icon, network security config | Builds, installs, is visibly a PiercingXX app |
| 2 | `core/` — `Frontmatter` (parse/render), `Diff3`, **`ChecklistMerge` (§7.1)**, `Slug`, `Ulid`, `SyncPolicy`, `Verdict`; the full §16 JVM suite | **Every row of the §6 table has a named passing test; the "no verdict reduces recoverable bytes" property test is green; and the checklist boolean merge is proven exhaustive over all its state combinations** |
| 3 | `data/` — Room + FTS, the `filesDir/vault` mirror with write-temp-then-rename, `VaultStore`, `AttachmentStore` skeleton | A vault round-trips: read → edit → re-read, no network involved anywhere in the call graph |
| 4 | `net/` — `WebDavClient`, `PropfindParser` against real DSM output captured in WS0, `OneHostInterceptor`, `CredentialVault` (StrongBox seal/unseal with fallback) | App-side behavior matches the WS0 `curl` transcripts byte for byte; the one-host suite passes including redirects and IP literals |
| 5 | `sync/` — `SyncEngine`, `Outbox` (idempotent, survives reboot), `MergeEngine`, `ConflictNamer`, WorkManager wiring per §4.4, the >25% vault-safety stop | **The headline works, headless: edit the same note on the phone and in Obsidian between syncs, get a clean merge or two visible notes — never one silently overwritten note.** Plus the R3 test by hand: clear app data, resync, nothing lost |
| 6 | Setup — host/port, account, test, `PROPFIND`-browse the share, confirm what was found, the import disclosure ("47 files will be given ids"), device name, first sync with a count | A fresh install reaches a synced vault with no developer present and no adb |
| 7 | Grid + capture bar + editor — staggered cards, Pinned/Others, inline Markdown rendering, tappable checkboxes, debounced save, tones per O2 | **Daily-driver gate: replace Keep for real, on the real vault** |
| 8 | Labels, archive, trash with days-remaining, FTS search, long-press multi-select, drawer; checklist drag-reorder and the sort-to-bottom animation over WS2's merge | Keep parity complete |
| 9 | Sync screen — connection state in words, outbox with per-note reasons, conflict **Resolve** sheet, full log, weekly tallies, **Test connection** running the real client | R10 complete: every sync outcome explains itself, including the boring ones |
| 10 | Attachments — camera/gallery insert, SHA-256 addressing, upload-before-body ordering, lazy download, cache budget + eviction, orphan sweep, EXIF strip, HEIC→JPEG | v1 |

WS3–5 produce a functionally complete sync engine with no UI at all. UI is
deliberately late: the part that has to be right is the part that can be
proven right without a screen, and a merge bug found in WS9 is a merge bug
found on real notes.

## Standing rules

- A workstream with failing tests is not done.
- **The R3 test runs at every exit from WS5 on:** clear app data, resync,
  diff the vault. Anything lost is a release blocker, not a bug report.
- `aapt2 dump permissions` at every WS exit shows `INTERNET`,
  `ACCESS_NETWORK_STATE`, `CAMERA`, `POST_NOTIFICATIONS`, and nothing else.
  Zero storage permissions is a design claim (D14) and it rots exactly once.
- The dependency tree is checked at every WS exit for anything that talks to
  a network on its own — analytics, crash reporting, Play Services,
  Firebase. One host means one host (R8).
- Instrumented tests for WS4–5 run against the operator's real DSM over
  Tailscale. There is no emulator for a Synology.
