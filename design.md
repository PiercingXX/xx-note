# XX-Note — Design Specification

Android notes app for GrapheneOS. Google Keep's hands on a folder of
Markdown: a card grid, a fast editor, checklists, labels, pin, archive,
search — and every note is a `.md` file on your own Synology, reached over
Tailscale, readable in any text editor forever.

The sync engine is the actual point. Keep's UI is a solved problem and this
app reimplements it because it is the right shape for capture-and-glance.
What is not solved is a phone notes app whose save format is a plain file
you own, on hardware you own, that survives the app being deleted, the
company folding, and the format going out of fashion. That is §6 through
§10, and everything else is in service of it.

**Status:** built and JVM-tested — the app and core unit suites run green in
CI. Device-unproven: WS0 has not run, and androidTest has never executed on
hardware.
**Build plan:** [todo.md](todo.md) — workstreams, gates, and the order to do them in.
**Screens:** [design/xx-note-screens.html](design/xx-note-screens.html) — the mockup.
**Research:** [design/research.md](design/research.md) — sourced findings behind this spec.
**Target:** Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.

---

## 1. Cleanroom provenance

This repository is **all rights reserved**. The FOSS Android notes field is
almost entirely copyleft — Fossify Notes and Notally are GPL-3.0, Joplin is
AGPL-3.0 (doubly radioactive), Standard Notes is AGPL. Copying any of it
would force this project to the same terms.

**What was studied:** official Android documentation, Synology's Knowledge
Center and WebDAV Server help pages, Tailscale's documentation, RFC 4918,
the CommonMark and GFM specifications, published screenshots, Play and
F-Droid listings, reviews, and GrapheneOS forum threads. Full citations in
[design/research.md](design/research.md).

**What was never opened:** the source of Fossify Notes, Simple Notes,
Notally, Joplin, Standard Notes, Orgzly, or any Keep client.

**What may be consulted:** the CommonMark and GFM specs (they are
specifications, and implementing a spec is the opposite of copying),
RFC 4918 and RFC 6578 for WebDAV, Android and AndroidX documentation, and
**Markor** (Apache-2.0) as *behavior reference only* — Apache-2.0 carries
NOTICE obligations that do not belong in an all-rights-reserved repo, so it
is read the way AOSP is read in XX-Dialer: for what it does, never for how it
does it. Obsidian is proprietary and studied only through its published
vault-format documentation, which is what interoperability requires.

Anyone extending this project holds the same line: **read their docs, never
their source.**

### Prior art and what each contributed

| Project | License | What was taken (behavior only) |
|---|---|---|
| Google Keep | proprietary | The whole front end: a masonry card grid with a Pinned/Others split, capture that starts typing in one tap, checklists that reorder themselves, labels as a flat tag set rather than folders, long-press multi-select, archive as a first-class verb distinct from delete, and Trash with a fixed expiry. Fifteen years of proving that this is the right IA for notes you write in eleven seconds while walking. |
| Obsidian | proprietary | The vault as the product: a directory of `.md` files with YAML frontmatter, where the app is a viewer over data the user owns. Its frontmatter conventions are the interop target — a vault written by XX-Note opens in Obsidian with labels as tags and no import step. |
| Markor | Apache-2.0 — docs only | Proof that a files-first Markdown notes app is viable on Android with no database of record, and that users of that app want exactly one thing the file-agnostic editors miss: Keep's grid. |
| Joplin | AGPL — never opened | Proof of the sync-engine shape (local store + remote store + a resolver), and the cautionary tale: its sync target abstraction is powerful and its conflict output is a wall of `conflict` notes users do not know what to do with. §7 exists because of that. |
| Syncthing | MPL-2.0 — docs only | Conflict copies as the correct disposition — never merge silently, never drop, rename and keep both. The naming convention that made it legible. |
| Synology Drive | proprietary | The conflict-file naming format XX-Note deliberately imitates so its forks look native in Drive's own web UI (§7); server-side versioning that the operator gets for free on the same folder. |
| Git | GPL-2.0 — the algorithm is published | Three-way merge with a common ancestor. XX-Note stores a base snapshot for exactly this reason (§6); the merge is line-based diff3, which is a published algorithm, not their code. |

---

## 2. Requirements

**R1.** Capture is one tap from cold start to a blinking cursor. A notes app
that loses a thought to a loading spinner has failed at its only job.
**R2.** Every note is exactly one UTF-8 `.md` file with a YAML frontmatter
block. No proprietary container, no sidecar, no database of record.
**R3.** The vault is the source of truth. Room is a cache and an outbox.
Deleting the app's data and re-syncing must lose nothing.
**R4.** The app works fully offline. Every read, write, search, and label
operation completes with no network. Sync is a background reconciliation,
never a precondition for using the app.
**R5.** **Never lose text.** No sync outcome may reduce the bytes the user
can still read. Deletes become trash, conflicts become forks, an edit always
outranks a delete. This is the analogue of XX-Dialer's fail-open law and it
is enforced the same way — as a property test over the whole §6 table.
**R6.** A note edited on the phone and in Obsidian on the desktop between
syncs produces either a clean three-way merge or two visible notes. It never
produces one silently-overwritten note.
**R7.** Note identity survives renaming. A note renamed in Obsidian, or
moved between folders, is the same note — matched by its frontmatter `id`,
not its path.
**R8.** **One host, no third party.** The app declares `INTERNET` — it must,
to reach the NAS — and reaches exactly one origin: the Synology at the
Tailscale address the operator configured. Enforced three ways: an OkHttp
interceptor that throws on any other host, a network security config that
trusts only that domain and forbids cleartext, and a dependency allowlist in
CI. No analytics, no crash reporting, no ad SDK, no Google Play Services, no
Firebase. On GrapheneOS the Network permission is visible and revocable, and
revoking it degrades XX-Note to local-only rather than breaking it.
**R9.** Credentials never touch plain storage. The DSM account password is
sealed with a hardware-backed Keystore key (StrongBox on `caiman`) and the
ciphertext lives in Room.
**R10.** Sync state is always legible. One screen states the connection, the
last successful sync, what is queued, what conflicted, and why — and offers
a **Test connection** that runs the real code path. No silent spinner, no
"synced" that isn't.
**R11.** Attachments are files too: stored beside the notes, referenced by
relative Markdown links, resolvable by any other Markdown reader.
**R12.** Nothing this app writes requires this app to read.

### Non-goals

- **Reminders and notifications.** Deferred, not rejected — an exact-alarm
  permission, boot re-arming, and a recurrence model are their own
  workstream, and shipping them half-done means missed reminders, which is
  worse than no reminders. Frontmatter reserves the `reminder:` key so v1
  vaults are forward-compatible.
- **Home-screen widget, quick-settings tile, and share-to-note.** Same
  ruling: the capture surfaces are the obvious v2, and v1's job is to prove
  the vault format and the sync engine.
- **Collaboration, sharing, and multi-user notes.** Keep's collaborator
  feature needs a server that arbitrates. XX-Note's server is a file share.
  Two people editing one vault get §6's conflict handling and nothing more,
  which is honest but is not collaboration.
- **Drawings and handwriting.** No sane Markdown representation exists.
- **Rich text beyond Markdown.** No colored spans, no fonts, no tables
  editor. The file has to stay readable.
- **End-to-end encryption of the vault.** The transport is TLS over
  Tailscale's WireGuard and the disk is the operator's own NAS; encrypting
  the note bodies would make them unreadable in Obsidian and on the NAS,
  which defeats R2 and R12. If the threat model ever includes the NAS
  itself, that is DSM's encrypted-shared-folder feature, below this app.
- **Cloud accounts of any kind.** No Google, no Dropbox, no S3. One host.
- **Real-time sync.** WebDAV has no push. Sync happens on foreground, on
  save (debounced), on pull-to-refresh, and opportunistically in the
  background (§4.4). The app says so plainly rather than implying live sync.
- **Telemetry, analytics, crash reporting.**
- **A second sync backend.** The abstraction to add one is free (§5), but
  shipping only one keeps its edges honest.

---

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **The vault is truth; Room is a cache and an outbox** | R3. It makes the strongest claim the app can make — wipe the app, resync, lose nothing — and it forces every feature to have a file representation, which is the discipline that keeps R12 true. The cost is that some things Keep does cheaply in a DB (ordering, colors) have to earn a place in frontmatter. |
| D2 | **One note = one `.md` file with YAML frontmatter** | Chosen over sidecar JSON because a half-synced pair orphans metadata silently, and over folder/filename encoding because it cannot express multi-label or color at all. Frontmatter is what Obsidian, Dataview, Hugo, and Jekyll already read, so interop is free rather than built. |
| D3 | **Identity is a ULID in `id:`, not the filename** | R7. Filenames are human slugs derived from the title and are expected to change — by the user in Obsidian, by a retitle in the app. Binding identity to the path would turn every rename into a delete-plus-create, which is a data-loss shape. ULID over UUIDv4 because it sorts lexically by creation time, so a directory listing is chronological for free. |
| D4 | **WebDAV over HTTPS to DSM, on the Tailscale MagicDNS name** | A standard protocol with a published RFC, one OkHttp client, no vendor SDK, and `curl`-reproducible failures. `PROPFIND` gives listing plus `getetag` plus `getlastmodified` in one round trip; `If-Match` gives lost-update protection. The Synology Drive Web API would give cheaper deltas but couples the app to undocumented DSM endpoints; SMB is a LAN protocol behaving badly over a WAN-shaped link; SFTP has no ETags. |
| D5 | **XX-Note does not embed Tailscale, and does not try** | Android permits one VPN at a time, and the Tailscale client owns it. XX-Note is an ordinary HTTP client that happens to resolve a MagicDNS name; if the tailnet is down the request fails like any other network failure and the outbox waits. Embedding a second WireGuard stack to avoid a dependency the operator already runs would be strictly worse and unshippable alongside the real client. |
| D6 | **`SyncPolicy.decide(base, local, remote)` is pure and imports nothing from `android.*`** | The XX-Dialer discipline, unchanged: the part that must be correct is the part that can be proven correct on the JVM with no device. The §6 table is a truth table and it is tested as one. |
| D7 | **Three-way merge with a stored base snapshot; unmergeable hunks fork** | R6. Two-way sync without a common ancestor cannot distinguish "they added a line" from "I deleted a line," so it must either ask the user every time or guess wrong. The base snapshot costs one extra body per note in Room and removes the guess. |
| D8 | **Conflicts fork into a second visible note using Synology's own naming** | `<slug>_<device>_<Aug-23-1004-2026>_EditConflict_1.md`. Imitating Drive's convention means a fork looks native in Drive's web UI and in the desktop client's conflict tooling, instead of introducing a third vocabulary into a folder that already has one. The fork gets a fresh `id` and a `conflictOf:` back-reference so the app can offer a side-by-side resolve. |
| D9 | **Delete is a move to `.xxnote/trash/`, never an unlink** | R5, and Keep already works this way, so the safety mechanism and the product feature are the same mechanism. It also solves the resurrection problem: without a tombstone, a peer that still holds the file re-creates it on the next sync forever. Trash expires at 7 days (Keep's rule, adopted verbatim) and expiry is the only path to a real unlink. |
| D10 | **An edit outranks a delete, always** | §6 rows 8 and 10. If one side deleted a note and the other side edited it, the edit wins and the note comes back. The deleting user loses a click; the editing user would otherwise lose writing. R5 decides it. |
| D11 | **Compose, not Views** | Operator ruling, and the first case in the family where the work argues for it independently: a live Markdown editor with inline checkbox toggling, a staggered grid with drag reorder, and animated multi-select are all things Views makes actively painful. Unlike a dialer there is no cold-inflate deadline — nothing here has to appear before the user has finished pressing. Follows the XX-Vitals precedent. |
| D12 | **Note "colors" are tonal, not chromatic** | Keep ships twelve hues; the brand ships one accent and no hue. Rather than break the brand or drop the feature, the twelve become six surface tones on the AMOLED ladder (`ink` → `ink-raised` → `graphite` → `slate`, plus two hairline treatments). The frontmatter key stays `color:` with Keep-compatible names so an imported vault round-trips, and the renderer maps name → tone. **Settled (O2): the brand wins.** This was the one place where cleanroom fidelity and the brand actually disagreed, and the brand rule is older than this app. |
| D13 | **Attachments are content-addressed and therefore immutable** | `attachments/<sha256-prefix>.<ext>`, referenced as a relative Markdown link. Immutable binaries cannot conflict, cannot merge, and cannot be half-updated — the entire binary sync problem collapses into "does this file exist on the far side." Deduplication across notes is a free consequence. |
| D14 | **No storage permission at all** | The local mirror lives in `filesDir/vault` — app-private, invisible to Storage Scopes, requiring no grant and surviving no prompt. Exporting a copy to a user-visible folder is a SAF document-tree action the user initiates, which needs no permission either. |
| D15 | **A dedicated DSM account, WebDAV-only, scoped to one shared folder** | DSM's WebDAV does Basic auth over TLS and does not do 2FA, so the credential in the app must be worth as little as possible. Setup instructs creating an `xxnote` user with the WebDAV application permission and read/write on the notes folder alone — no admin, no other shares, no DSM login. |
| D16 | **Room + FTS4/5 for search, Gson for backup JSON, `com.piercingxx.xxnote`** | Same stack as the family; the backup JSON conventions carry over. Search is over the cache, not the network — R4. |
| D17 | **No native dependencies** | Keeps the app MTE-clean on `caiman` under GrapheneOS's hardened memory allocator, and keeps the APK auditable. Markdown parsing, YAML, diff3, and hashing are all JVM. |
| D18 | **Checked items sort to the bottom of their list block, and checklists merge item-wise** | Operator ruling — and it forces a better engine than the display-only alternative it replaced. A line-based diff3 over a list that reorders itself would fork on nearly every concurrent tap, so `type: checklist` gets its own merge path (§7.1): items are matched across all three snapshots and each item's checked state merges as a three-way boolean, which **can never conflict** — from a given base, both sides can only move a boolean the same direction. Adds and removes merge as a set, and an edited item outranks a removed one (D10 restated at item level). Only a same-item text edit on both sides can fork. |
| D19 | **Reordering happens on a user edit in XX-Note, never on pull and never on sync** | Obsidian and the desktop do not know the sort rule, so a file arriving from the NAS has its checked items wherever its author left them. Reordering on pull would rewrite a file the user never touched, push it back, and start an ordering war between two clients that neither can win. A pulled file renders as-is and is normalized only when the user edits it here. |

---

## 4. Platform mechanics

The design rests on these. Facts marked **[VERIFY]** are unconfirmed against
the operator's actual DSM build and GrapheneOS install, and are WS0's job to
prove on-device before anything is built on them (§17).

### 4.1 Reaching the NAS

Tailscale on Android is an ordinary app holding the system `VpnService`.
When it is up, its MagicDNS names resolve device-wide and any app's sockets
route to the tailnet — XX-Note needs no Tailscale integration, no SDK, and
no awareness beyond "the name resolved or it didn't." App-based split
tunnelling (Tailscale 1.70+) lets the operator include or exclude XX-Note
specifically; the app must behave correctly on the excluded side of that
setting, which is indistinguishable from the tailnet being down.

Android permits exactly one active VPN. If the operator runs another VPN,
Tailscale is not up, and XX-Note is offline. This is stated in Setup rather
than diagnosed at runtime.

**Certificates.** Tailscale can provision real Let's Encrypt certificates
for `*.ts.net` MagicDNS names, so DSM can serve valid TLS on
`nas.<tailnet>.ts.net:5006` with no pinning, no user-installed CA, and no
`cleartextTrafficPermitted`. **[VERIFY]** that the operator's DSM is serving
the Tailscale-issued certificate on the WebDAV listener specifically and not
only on the DSM web UI — DSM binds certificates per service.

### 4.2 DSM WebDAV

WebDAV Server is a separate package from Package Center, not a DSM
built-in. Once installed: Settings → HTTP/HTTPS, enable HTTPS. Defaults are
**5005 HTTP, 5006 HTTPS**; HTTP is disabled outright (R8's config forbids
cleartext, so an accidental 5005 fails closed).

The share layout matters. Synology Drive's "My Drive" is the `Drive`
directory inside the user's home — `/homes/<user>/Drive` on the filesystem —
and WebDAV exposes shared folders by name, so the vault lands at roughly
`https://nas.<tailnet>.ts.net:5006/home/Drive/Notes/`. **[VERIFY]** the
exact path prefix DSM's WebDAV presents for the per-user `home` share, and
whether the dedicated `xxnote` account (D15) sees it at the same path — a
service account with no Drive of its own may need a plain shared folder
instead, in which case the vault moves out of My Drive and Synology Drive's
own client is pointed at it as a synced folder. Either arrangement works;
the spec does not depend on which.

Methods used: `PROPFIND` (Depth 1) to list, `GET` to read, `PUT` to write,
`MOVE` for renames and trash, `MKCOL` for the folder skeleton, `DELETE` for
trash expiry, `HEAD` for the connection test.

**[VERIFY] ETags.** The whole optimistic-concurrency scheme (§6 rows 4, 6,
12) rests on DSM returning a stable, strong `getetag` in `PROPFIND` and
honoring `If-Match` on `PUT`. Two known hazards: implementations that return
weak ETags, which `If-Match` may not use, and implementations whose
`PROPFIND` `getetag` differs from the `ETag` header on `GET`. WS0 measures
both. **If ETags are unusable, the fallback is size + `getlastmodified` +
a full-body SHA-256 on read**, which costs a `GET` per candidate and makes
the lost-update race narrow instead of closed — a materially worse app, and
better known before it is built.

### 4.3 The Markdown

CommonMark plus the GFM task-list extension is the parse target. The editor
renders inline — headings, emphasis, links, code, lists, and checkboxes are
styled in place rather than in a preview pane, because a preview toggle is
where note-taking apps go to become document editors.

Frontmatter is a YAML block delimited by `---` on the first line. Parsing is
lenient by design: an unrecognized key is preserved verbatim on rewrite, and
a malformed block is treated as body text rather than discarded (R5 — a
parser bug must not eat a note). XX-Note owns the keys in §8 and touches no
others, so a vault shared with Obsidian plugins keeps their metadata.

### 4.4 Background execution

Sync is WorkManager, not a foreground service. `dataSync` foreground
services are capped at six hours per twenty-four on API 35+ and throw
`ForegroundServiceStartNotAllowedException` past the budget, and on Android
16 jobs started from a foreground service obey their own runtime quotas
anyway. A notes app has no business holding that budget.

- **Foreground sync** — the common case. Expedited `OneTimeWorkRequest` on
  app resume, on debounced save, and on pull-to-refresh.
- **Background sync** — `PeriodicWorkRequest`, 15-minute floor, network
  constraint. Doze will defer it and that is acceptable and stated.
- **User-initiated data transfer job** — only for an operator-tapped "Sync
  everything now" over a large vault or a batch of attachments, which is
  exactly the exemption that class of job exists for.

The app never claims a note is synced because a job was enqueued. Sync
state is per-note and set on the response, not on the request.

### 4.5 GrapheneOS specifics

- **Network permission.** GrapheneOS exposes `INTERNET` as a revocable
  per-app toggle. Revoked, XX-Note must be a fully functional local notes
  app that says "network off — nothing is leaving this device" on the sync
  screen, rather than queueing silently forever or showing an error every
  ten seconds. This is R8 made visible, and it is a feature.
- **Storage Scopes** is not engaged at all, by D14.
- **No sandboxed Play Services**, no Firebase, no push. Poll-only sync is
  the consequence and is designed for, not worked around.
- **Hardened malloc and MTE** on `caiman` — no native code (D17), so
  nothing to reconcile.
- **StrongBox** — the Titan M2 backs the Keystore key sealing the DSM
  credential (R9). **[VERIFY]** `setIsStrongBoxBacked(true)` succeeds for an
  AES-GCM key on this build; the documented fallback is catching
  `StrongBoxUnavailableException` and re-deriving without it.

---

## 5. Architecture

```
                    ┌────────────────────────────────────┐
                    │         SyncPolicy (pure)          │
                    │   decide(base, local, remote)      │
                    │   imports nothing from android.*   │
                    │   → Pull · Push · Merge · Fork ·   │
                    │     Trash · Resurrect · Nothing    │
                    └───────▲──────────────────▲─────────┘
                            │                  │
              per-note      │                  │   per-note
              verdicts      │                  │   verdicts
        ┌───────────────────┴───┐     ┌────────┴─────────────────┐
        │      SyncEngine       │     │      MergeEngine         │
        │  plan → apply → log   │     │  prose: diff3 · lists:   │
        │  WorkManager-driven   │     │  item-wise · frontmatter │
        │  bounded retries      │     │  key-wise · fork on hunk │
        └───────────▲───────────┘     └────────▲─────────────────┘
                    │                          │
      ┌─────────────┴──────────────────────────┴──────────────┐
      │                      VaultStore                       │
      │  Room: notes cache · base snapshots · outbox · log     │
      │  filesDir/vault: the local mirror of the .md files     │
      │  FTS index over title + body                           │
      └───────────────────────────▲───────────────────────────┘
                                  │
                    ┌─────────────┴──────────────┐
                    │       WebDavClient         │
                    │  OkHttp · one-host guard   │
                    │  PROPFIND/GET/PUT/MOVE     │
                    └────────────────────────────┘
```

`SyncPolicy` computes *what should happen* to one note; `SyncEngine` applies
it and is the only component allowed to touch both sides. The UI never calls
the network — it edits the local mirror and Room, and a sync happens or
doesn't. That separation is what makes R4 structural rather than aspirational.

The split of labor:

| Stage | Sees | May decide | May not |
|---|---|---|---|
| UI / editor | the local mirror only | create, edit, trash, restore, label | anything about the remote — it cannot even observe it |
| `SyncEngine` | both sides + base | apply a verdict, retry, log | invent a verdict of its own |
| `SyncPolicy` | three content snapshots | the verdict | perform I/O, know what a file is, or import `android.*` |
| `MergeEngine` | three bodies | a merged body or a refusal | resolve a conflict by choosing a side |
| `WebDavClient` | one host | transport | any host but the configured one |

`MergeEngine` refusing is a first-class, expected outcome, not an error. A
merge engine that never refuses is one that loses text.

---

## 6. The sync table

Per note `id`, first match wins, top to bottom. `base` is the snapshot from
the last successful sync — body hash plus ETag. "Dirty" means differs from
base.

| # | Local | Remote | Verdict | What happens |
|---|---|---|---|---|
| 1 | absent, no base | present | **Pull** | `GET`, write the mirror, record base |
| 2 | present, no base | absent | **Push** | `PUT` with `If-None-Match: *` |
| 3 | clean | clean | **Nothing** | — |
| 4 | dirty | clean | **Push** | `PUT` with `If-Match: <base etag>` |
| 5 | clean | dirty | **Pull** | `GET`, overwrite the mirror, adopt the ETag |
| 6 | dirty | dirty | **Merge** | diff3 against base; clean → push the merge; any conflicted hunk → **Fork** |
| 7 | trashed | clean | **Trash** | `MOVE` to `.xxnote/trash/` |
| 8 | trashed | dirty | **Resurrect** | remote wins: restore it as a live note, keep the local trashed copy in trash (D10) |
| 9 | clean | gone, base existed | **Trash local** | move the local file to trash — never unlink (D9) |
| 10 | dirty | gone, base existed | **Resurrect** | re-`PUT` the local note; the edit outranks the delete (D10) |
| 11 | present, no base | present, no base, differing bodies | **Fork** | two files claim one `id`; keep both, fork the newer |
| 12 | `If-Match` rejected mid-push | — | **Re-plan** | re-read the remote, re-enter at row 1; after 3 rounds, **Fork** |

**The invariant, tested as a property over every row:** no verdict reduces
the number of distinct bytes the user can still open. Deletes go to trash,
conflicts go to two notes, and every ambiguity resolves toward more text.

Restated as the family law: **every failure keeps writing, never loses it.**

Rows 8 and 10 are the two that will feel wrong to somebody at some point —
"I deleted that and it came back." The Rules-equivalent screen says so in
words, the trash keeps the deleting side's copy, and the alternative is
silently discarding an edit somebody made. That trade is not close.

---

## 7. Conflicts

A fork is a second real note, not a hidden state.

**Naming** follows Synology Drive's own convention (D8) so it reads as
native in Drive's web UI:

```
grocery-list_pixel9_Aug-23-1004-2026_EditConflict_1.md
```

`<slug>_<device>_<Mon-DD-HHMM-YYYY>_EditConflict_<n>.md`. The device name is
set in Setup and defaults to the model. `<n>` increments until the name is
free.

**Frontmatter** on the fork gets a fresh `id`, `conflictOf: <original id>`,
and `conflictAt:`. The original keeps its `id` and is untouched by the fork's
existence, so the far side never sees its note change identity.

**In the UI**, forks surface three ways: a persistent count on the sync
screen, a badge on the original note's card, and a **Resolve** sheet showing
the two bodies side by side with the diff3 markers already applied — accept
mine, accept theirs, or edit the merged text directly. Resolving trashes the
fork and pushes the result.

**What is never done:** last-write-wins by timestamp. Clock skew between a
phone and a NAS is real, `getlastmodified` has one-second resolution, and
"newest wins" is how sync engines lose an afternoon of writing without
telling anyone. XX-Note has no timestamp-based resolution path at all — not
as a fallback, not as a setting.

The frontmatter itself merges key-wise rather than by diff3, since it is
structured: `labels` union, `pinned`/`archived` OR-ed toward the more
visible state, `updated` takes the later, `color` takes the local side, and
an unknown key present on both with different values is the one frontmatter
case that forks.

### 7.1 Checklists merge item-wise, not line-wise

D18 makes a checklist reorder itself, which makes line-based diff3 the wrong
tool. On the far side, one person ticking item 2 and another ticking item 5
both look like *a line was deleted from the middle and a line was appended at
the end* — and the two appends land in the same region. Prose merges by
lines; a list merges by items.

When `type: checklist` holds on all three snapshots, `MergeEngine` parses each
contiguous GFM task-list block into items and merges those instead:

| Case | Resolution |
|---|---|
| Checked state differs | Three-way boolean: whichever side moved away from base wins. If both moved, they necessarily moved the same way — so **a checkbox can never conflict.** This is the whole reason D18 is safe. |
| Item present on one side, absent in base | Added — keep it |
| Item absent on one side, present in base and untouched on the other | Removed — drop it |
| Item removed on one side, text-edited on the other | The edit wins (D10 at item level) |
| Item text edited differently on both sides | **The only forking case**, and it forks the note, not the item |
| Authored order differs | Merged as a sequence of item identities; the rendered order is that sequence stably partitioned by checked state, so order is *derived* and cannot itself conflict |

**Item identity is the hard part.** An item whose text was edited must not
read as "removed, and a different one added" — that would silently drop its
checked state, which is precisely the class of loss R5 forbids. Matching runs
in two passes: exact match on normalized text, then best-similarity pairing of
the leftovers above a threshold. Below the threshold the items are genuinely
different and become an add plus a remove. **An ambiguous pairing forks rather
than guessing.**

No hidden identifiers are written into the file to make this easier. An
`<!-- id -->` comment per item would make the merge trivial and would make the
file worse to read in every other editor, which is the thing this app exists
not to do (R2, R12). The heuristic is the price of a clean file, and it is
paid in test coverage (§16).

Items sort within **their own contiguous list block**, never to the bottom of
the file. A note with prose after its checklist keeps the prose where it was;
a note with two separate task lists sorts each independently.

Notes whose `type` is not `checklist` are never reordered, even when they
contain task lists. `type` is therefore behavioral, not cosmetic — see §8.

---

## 8. The vault format

```
Notes/
├── 01J9F2K3M4N5P6Q7R8S9T0V1W2-grocery-list.md
├── 01J9F2K8ZZ1A2B3C4D5E6F7G8H-standup-notes.md
├── attachments/
│   ├── 3f9a2c81b4e07d65.jpg
│   └── a71c04e9f2830b5d.png
└── .xxnote/
    ├── trash/
    │   └── 01J9EX...-old-idea.md
    └── device.json          ← device name + vault format version
```

Filenames are `<ulid>-<slug>.md`. The ULID prefix makes a directory listing
chronological and makes collisions impossible; the slug makes it human. Both
are cosmetic — `id:` in the frontmatter is the identity (D3), and a file
renamed to anything at all is still found by its `id`.

A note:

```markdown
---
id: 01J9F2K3M4N5P6Q7R8S9T0V1W2
title: Grocery list
created: 2026-08-23T10:04:12Z
updated: 2026-08-23T10:07:55Z
pinned: true
archived: false
color: sand
labels: [home, errands]
type: checklist
---

- [ ] oat milk
- [ ] coffee, the dark one
- [x] bin bags
```

| Key | Type | Notes |
|---|---|---|
| `id` | ULID | immutable identity. Absent → the app assigns one on first read and rewrites the file. |
| `title` | string | may be empty; the grid falls back to the first body line, as Keep does |
| `created` / `updated` | RFC 3339 UTC | `updated` is content-modified, not synced-at |
| `pinned` / `archived` | bool | mutually compatible; pinned-and-archived is legal and Keep-consistent |
| `color` | Keep colour name | rendered as a surface tone (D12) |
| `labels` | string list | flat, unordered, case-preserving and case-insensitive on match. Obsidian reads these as tags. |
| `type` | `note` \| `checklist` | **behavioral, not cosmetic** — `checklist` enables the sort-to-bottom rewrite (§7.1, D18). An unknown or corrupted value degrades to `note`, which does *less* to the file: the safe direction |
| `reminder` | reserved | unused in v1 (non-goal), reserved so v1 vaults stay forward-compatible |
| `conflictOf` / `conflictAt` | ULID / RFC 3339 | present only on forks (§7) |

Checklists are GFM task lists, and in a `type: checklist` note **checked items
sort to the bottom of their own list block on save** (D18). The rewrite happens
once per debounced save, not once per tap, and only when the user edited the
note in XX-Note — never on a pull (D19). Concurrent tapping is safe because
checklists do not merge by lines (§7.1).

Unrecognized frontmatter keys are preserved byte-for-byte through any
rewrite (§4.3).

---

## 9. Identity, rename, and delete

**Rename.** A file whose path changed but whose `id` matches a known note is
a move, applied to the mirror and nothing else. This is the difference
between "I renamed a note in Obsidian" and "I deleted a note and made a new
one," and only the frontmatter can tell them apart (D3).

**Retitle in-app.** Changing the title regenerates the slug and `MOVE`s the
remote file. The `id` prefix is unchanged, so the far side sees a rename it
can follow. If the `MOVE` fails, the note is still correct — the filename is
cosmetic — and the rename retries on the next sync.

**Delete.** `MOVE` to `.xxnote/trash/`, `updated` stamped, and the frontmatter
gains `trashedAt:`. Trash is browsable, restorable, and expires at 7 days —
expiry is the only code path in the app that issues a `DELETE`, it runs only
against files carrying `trashedAt`, and it is the one place worth a
belt-and-braces assertion.

**Vault-level safety.** A sync that would trash more than 25% of the vault in
one pass stops and asks. That shape is almost always a misconfigured path, a
wrong account, or an empty share mounted over the right one — and it is
exactly how file-sync tools destroy data. The threshold is a setting; the
prompt is not skippable.

---

## 10. Attachments

Insert from camera or gallery → the bytes are hashed (SHA-256), written to
`attachments/<first-16-hex>.<ext>`, and referenced from the body as
`![](attachments/3f9a2c81b4e07d65.jpg)`. Any Markdown reader resolves it;
Obsidian shows the image with no configuration.

Content addressing (D13) does the heavy lifting: the file is immutable, so
it can never conflict or half-update, and the sync question collapses to
`PROPFIND` says it exists or it doesn't. The same photo pasted into three
notes is one file.

- **Upload** is part of the note's push and must land *before* the note body
  that references it, so the far side never sees a broken link.
- **Download** is lazy — the grid renders a placeholder at the stored
  dimensions and fetches on first view. A note is readable before its
  images arrive.
- **Cache budget** is a setting (default 500 MB), evicted least-recently-
  viewed. Eviction removes the local copy only; the remote is truth.
- **Orphans** — an attachment referenced by no note is reported on the sync
  screen with a count and a one-tap sweep. Never swept automatically: a
  reference may live in a note this device has not pulled yet.
- **HEIC** from the Pixel camera is transcoded to JPEG on insert, because
  "readable by anything" is the entire point (R11). Original EXIF location
  data is stripped on insert, unconditionally.

---

## 11. Data model (Room)

```
note            id TEXT PK, path TEXT, title TEXT, body TEXT,
                created INTEGER, updated INTEGER, pinned INT, archived INT,
                color TEXT, type TEXT, trashedAt INTEGER NULL,
                conflictOf TEXT NULL, extraFrontmatter TEXT NULL
note_fts        FTS over (title, body)  ← external content, id-linked
base_snapshot   id TEXT PK, body TEXT, frontmatter TEXT,
                etag TEXT NULL, remoteMtime INTEGER, syncedAt INTEGER
outbox          id INTEGER PK, noteId TEXT, op TEXT
                ('put'|'move'|'trash'|'delete'|'attach'), payload TEXT,
                attempts INT, lastError TEXT NULL, queuedAt INTEGER
label           name TEXT PK, sortIndex INT
note_label      noteId TEXT, name TEXT, PK(noteId, name)
attachment      hash TEXT PK, ext TEXT, bytes INTEGER, w INT, h INT,
                localPath TEXT NULL, lastViewedAt INTEGER, remoteKnown INT
sync_log        id INTEGER PK, at INTEGER, noteId TEXT NULL, verdict TEXT,
                reason TEXT, ok INT, detail TEXT NULL
setting         key TEXT PK, value TEXT
credential      id INTEGER PK (=1), host TEXT, basePath TEXT, user TEXT,
                sealedSecret BLOB, keyAlias TEXT
```

- `base_snapshot` is the entire reason three-way merge is possible (D7). It
  holds the body *as last agreed with the server*, which is neither side's
  current state and cannot be reconstructed from either.
- `note.body` duplicates the mirror file on disk. The file is truth (D1);
  the column exists so the grid and FTS never touch the filesystem. On any
  disagreement, the file wins and the row is rebuilt.
- `outbox` survives process death and reboot. Ops are idempotent by
  construction — a `put` carries the body it meant to write, so a replay
  after an unknown-result failure re-enters §6 rather than blindly retrying.
- `sync_log` is the reason-string store behind R10 and feeds the sync
  screen; capped at 1000 rows, pruned oldest-first — same shape and same cap
  as XX-Dialer's `screen_log`.
- `credential.sealedSecret` is AES-GCM ciphertext under a StrongBox-backed
  Keystore key (R9, §4.5). The plaintext exists only on the stack, only
  while a request is being signed.
- Backup/restore is the family Gson JSON: settings, labels, device name,
  vault config **without the credential**. Not the notes — the notes are the
  vault, and a backup format for them would be a second source of truth
  (D1). Restoring on a new device is: enter the vault details, sync.

---

## 12. UI

Compose (D11), AMOLED-black monochrome, matching the Launcher. Keep's
information architecture, reimplemented (§1).

1. **Grid** — the home surface. A staggered two-column card grid (one-column
   list is a setting), **Pinned** and **Others** sections with sticky
   headers. Cards show title, a body preview clipped at six lines, checklist
   progress as `3 / 7` in tabular Space Mono, a label row, an image
   thumbnail if the note has one, and a hairline in the note's tone (D12).
   Long-press enters multi-select — pin, label, colour, archive, delete
   across a selection. The top bar is search plus the drawer; there is no
   FAB. Capture lives in a persistent bottom **capture bar** — *Take a
   note…* plus checklist and camera glyphs — which is Keep's own answer to
   R1 and is better than a FAB because it starts the note in the surface
   the thumb is already on.
2. **Editor** — full-screen, title on top, body below, cursor placed and
   keyboard up before the transition finishes. Markdown renders inline:
   headings size, emphasis styles, links underline, code goes mono-boxed,
   `- [ ]` becomes a tappable box that toggles the underlying character.
   The bottom bar holds label, colour/tone, archive, attach, and overflow
   (copy, share as text, export, delete, note info). No preview toggle
   (§4.3). Saves are debounced at 800 ms to the mirror and Room, and every
   save enqueues a sync — the editor never blocks on either.
3. **Drawer** — Notes · Labels (each label a filtered grid) · Archive ·
   Trash (with days-remaining per note) · Sync · Settings. Label management
   is inline: create, rename with propagation across notes, delete with a
   count of what it will untag.
4. **Sync** — the product's honesty surface, and the analogue of XX-Dialer's
   Rules tab. Top: connection state in plain words — *Connected ·
   nas.tailnet.ts.net · last sync 14:02* / *Tailnet unreachable · 3 notes
   waiting* / *Network permission off · nothing is leaving this device*.
   Then the outbox with per-note reasons, the conflict list with **Resolve**
   (§7), attachment cache usage and orphan count, the vault path, and
   **Test connection** — which runs the real `PROPFIND` against the real
   client and prints what came back, verbatim, including the HTTP status.
   Then the sync log, full-length with reason strings, and the running
   tallies in Space Mono numerals: synced / merged / forked, this week.

**Setup** — first-run flow, and it is a real workstream, not a dialog: host
and port → account → **test** → pick or create the vault folder by browsing
the share over `PROPFIND` → confirm what it found (*empty folder* / *47
existing `.md` files — these will be imported, nothing will be overwritten*)
→ device name → first sync with a progress count. Each step shows its actual
state; a half-configured sync that looks configured is the failure this app
most needs to design against, exactly as XX-Dialer designs against a
half-configured dialer.

**Import** of an existing folder of Markdown is not a special mode — it is
§6 row 1, repeated. Files with no `id` get one written on first read, which
is the only time XX-Note modifies a file it did not create, and Setup says
so in those words before it happens.

### 12.1 Design tokens

Source of truth:
[`piercingxx-branding`](https://github.com/PiercingXX/piercingxx-branding) —
`BRAND-GUIDE.md` §3, vendored as `tokens/Tokens.kt` into the theme package
(Compose, so Kotlin rather than `android-colors.xml`), updated by
re-copying, never retyped. Both faces ship in `res/font/` (Space Mono
display, JetBrains Mono body), per the checklist.

The rules that bite here:

- **Reserved white:** body text caps at `text` 90%. The capture bar's active
  state and the current drawer item are the Signal moments. Strong emphasis
  inverts — white block, ink text.
- **One accent, no hue.** Note tones are surface elevations, not colours
  (D12): `ink` → `ink-raised` → `graphite` → `slate`, plus hairline-left and
  hairline-full for the last two. Six tones, twelve Keep names mapped onto
  them. `error` is reserved for the one loud state — a fork that has not
  been resolved.
- **Tabular figures everywhere digits live:** checklist progress, sync
  counts, trash days-remaining, byte counts, timestamps.
- XX-Note claims no product signal colour; family white stands.

Type ramp:

| Role | Face | Size |
|---|---|---|
| Editor title, empty-state headline | Space Mono | 28 / 24 sp |
| Card title, sync tallies | Space Mono | 16 sp |
| Editor body, card preview, list rows | JetBrains Mono | 16 / 13 sp |
| Labels, chips, eyebrows, sync reasons | JetBrains Mono | 11 sp, +0.08em tracking |

### 12.2 Theme sync

XX-Launcher broadcasts `xx.launcher.THEME_CHANGED` carrying a theme name and a
resolved background ARGB. XX-Note's exported receiver persists the choice and
repaints. Eight presets: AMOLED Night, Graphite, Forest Night, Ocean Drift,
Burgundy, Paper, Mist, Custom. Contrast is derived from the background rather
than carried in the broadcast, so the light grounds (Paper, Mist) get dark text
without a second message. There is no in-app picker — the launcher is the
picker, and the nine family apps all speak this contract, so the theme is set
once for the estate.

The receiver is exported and unguarded: the family contract carries no
permission, and the worst a spoofed broadcast buys an attacker is another valid
ground.

Verified live on the Pixel 6 — repainted to Graphite (`#131316`) and back.

---

## 13. Manifest

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<!-- deliberately NO storage permissions of any kind (D14) -->
<!-- deliberately NO exact alarms — reminders are a v1 non-goal -->
```

`android:usesCleartextTraffic="false"`, plus a network security config
naming exactly one `domain-config` — the operator's MagicDNS host — with
cleartext forbidden and the system trust anchor (the Tailscale-issued
Let's Encrypt certificate, §4.1). `CAMERA` is requested at the moment of
first capture, never at launch, and the app is fully usable if it is denied.

Components: a single `MainActivity` (Compose, `singleTop`), `SyncWorker` and
`AttachmentWorker` under WorkManager, a `BOOT_COMPLETED` receiver whose only
job is re-enqueuing the periodic worker, and a `FileProvider` for share-out.
No exported components beyond the launcher activity and the theme-sync receiver
(§12.2). No services of our own.

The manifest is short on purpose and the short list is the claim (R8): one
network permission, one camera permission, zero storage permissions, and
nothing that runs on its own schedule except a sync.

---

## 14. Package layout

```
com.piercingxx.xxnote
├── core/       SyncPolicy, Verdict, NoteSnapshot, Diff3, ChecklistMerge,
│               Slug, Ulid, Frontmatter, Markdown (AST)        ← pure JVM
├── sync/       SyncEngine, MergeEngine, Outbox, ConflictNamer,
│               SyncWorker, AttachmentWorker
├── net/        WebDavClient, PropfindParser, OneHostInterceptor,
│               CredentialVault (Keystore seal/unseal)
├── data/       entities, DAOs, XxDatabase, VaultStore (mirror I/O),
│               AttachmentStore, BackupJson
├── ui/         grid/, editor/, drawer/, sync/, setup/, theme/
└── util/       Hashing, ExifStripper, Heic
```

`core/` imports nothing from `android.*` and carries the correctness burden —
the §6 table, the diff3, the frontmatter round-trip. Same rule, same reason,
same test discipline as XX-Dialer's `core/`.

---

## 15. Failure modes

| Scenario | Required behaviour |
|---|---|
| Tailnet down, Tailscale off, or XX-Note excluded by split tunnelling | Indistinguishable and treated identically: offline. Full local function, outbox accumulates, sync screen says *unreachable* with the last-success time. No error toast, no retry storm. |
| Network permission revoked on GrapheneOS | Detected and stated: *network off — nothing is leaving this device*. Sync is not attempted at all; the outbox holds. Restoring the permission resumes without reconfiguration. |
| NAS reachable, WebDAV package stopped | HTTP failure with the status surfaced verbatim on the sync screen. Distinguished from "unreachable" because the fix is different and the user should not have to guess. |
| Auth fails (password changed, account disabled) | Sync halts, credential marked stale, one notification, Setup's account step reopens pre-filled except the secret. Never retried in a loop — a locked-out DSM account is worse than an unsynced note. |
| TLS certificate invalid or expired | Hard fail, no bypass, no "trust anyway" affordance anywhere in the app. The message names the host and says the certificate is the problem. |
| `If-Match` rejected (someone wrote between our read and our write) | §6 row 12 — re-read, re-plan, bounded to 3 rounds, then fork. Never a blind overwrite. |
| ETags unusable on this DSM | §4.2 fallback: size + `getlastmodified` + body hash. Detected once at Setup and stated on the sync screen, because the guarantee is genuinely weaker. |
| Merge produces a conflicted hunk | Fork (§7). Expected, not exceptional; the sync counts it as a success. |
| Checklist item pairing is ambiguous | Fork (§7.1). The heuristic never guesses a pairing it is not confident about: a fork costs a tap, and a wrong pairing costs a checked state silently. |
| A pulled file has checked items interleaved | Rendered exactly as it arrived. Nothing is rewritten until the user edits it here (D19), so XX-Note and the desktop never fight over ordering. |
| `type:` corrupted or unrecognized on a checklist | Degrades to `note` — no reordering, line-based merge. The failure does *less* to the file, which is the correct direction. |
| Malformed YAML frontmatter in a vault file | Treated as body text, note still opens, sync screen flags it. Never discarded, never "repaired" silently. |
| Two files carry the same `id` | §6 row 11 — fork the newer, both visible, flagged. |
| File in the vault with no `id` | One assigned and written back on first read — the only unsolicited write, disclosed in Setup (§12). |
| Disk full on the phone | Writes fail before the mirror is corrupted (write-temp-then-rename, always). Editor shows the failure; nothing is marked saved that was not. |
| Vault folder empty or wrong path mounted | §9 vault-level safety: >25% would-trash stops the sync and asks. |
| Note edited on the phone while the same note syncs | The editor writes to the mirror and Room; the engine planned against a snapshot. The push either succeeds against the old base or fails `If-Match` and re-plans. The user's keystrokes are never blocked and never discarded. |
| Attachment referenced but not yet downloaded | Placeholder at stored dimensions, tap to fetch. Note is readable regardless (R11). |
| Attachment upload succeeds, note push fails | Harmless — an orphan attachment, swept manually (§10). The order is deliberate for exactly this reason. |
| Clock skew between phone and NAS | Irrelevant by construction: no resolution path uses timestamps (§7). |
| Reboot mid-sync | Outbox replays; every op is idempotent and re-enters §6. |
| App data cleared | Full resync from the vault, which is truth (D1, R3). Only the credential and settings are lost, and only those are in the backup JSON. |

The failure direction is uniform: **every failure keeps text, and keeps
working locally.**

---

## 16. Testing

`SyncPolicy`, `Diff3`, `Frontmatter`, `Slug`, and `Ulid` are pure JVM and
carry the weight.

- **The full §6 table** — every row by name, plus the property test over all
  twelve: *no verdict reduces recoverable bytes*, asserted by generating
  random (base, local, remote) triples and checking that the union of
  readable content after applying the verdict is a superset of what only one
  side held.
- **Frontmatter round-trip** — parse → render is byte-identical for every
  fixture, including unknown keys, unusual scalars, empty values, CRLF, a
  BOM, no trailing newline, and a body that itself contains `---`.
- **Diff3** — clean merges (disjoint edits, additions at both ends,
  interleaved edits in one paragraph), and refusals (same line edited both
  sides, delete-vs-edit on one line, whole-body rewrite). A refusal is an
  asserted outcome, not a failure.
- **Checklist merge (§7.1)** — the three-way boolean over every (base, local,
  remote) state combination, asserting that **no combination can conflict**;
  item add/remove/edit as a set merge; edit-outranks-remove at item level;
  ambiguous pairing forks rather than pairs; identity survives a text edit
  (`coffee` → `coffee, the dark one` keeps its tick); sorting is per list
  block, not per file; a `type: note` file containing task lists is never
  reordered; a pulled file is not rewritten until it is edited here (D19).
- **Identity** — rename detection by `id` across a path change; retitle
  regenerating the slug without changing `id`; two files claiming one `id`;
  a file with no `id`.
- **Trash** — 7-day expiry boundary at 6d23h59m and 7d00h01m; expiry issues
  `DELETE` only for files carrying `trashedAt`; restore round-trip;
  resurrect (rows 8, 10) from both directions.
- **Vault safety** — the 25% threshold at 24%, 25%, and 26% of a vault.
- **Conflict naming** — collision counter, device names with spaces and
  non-ASCII, a slug at the filesystem length limit.
- **One-host guard** — the interceptor throws for every host but the
  configured one, including a same-name host on a different port, a redirect
  to a third party, and an IP literal that resolves to the right name.
- **Attachments** — hash stability, dedup across notes, eviction order,
  orphan detection, EXIF stripped, HEIC transcoded.
- **Instrumented, against the operator's real DSM over Tailscale**: WS0's
  probes re-run as tests; `PROPFIND` parse against real DSM output;
  `If-Match` honored or not (§4.2); `MOVE` semantics for rename and trash;
  a large vault's cold sync; Room migrations; Keystore seal/unseal with
  StrongBox and with the fallback path.

**A workstream with failing tests is not done.**

---

## 17. Build order

WS0 exists because the four facts the sync design leans on are unverified
against the operator's actual NAS. They cost a day and de-risk the entire
engine.

| WS | Scope | Gate |
|---|---|---|
| 0 | **Probe**, throwaway (a `curl` script is a legitimate deliverable here): does DSM's WebDAV return a strong, stable `getetag` in `PROPFIND` and honor `If-Match` on `PUT`? Does the `getetag` match the `GET` `ETag` header? What path does the `xxnote` account see the vault at (§4.2)? Is the Tailscale certificate served on 5006 specifically? Does StrongBox AES-GCM succeed on `caiman`? | **Everything.** If ETags are unusable, §4.2's fallback is adopted *before* the engine is written, not bolted on after. |
| 1 | Skeleton — gradle, manifest (§13), packages (§14), vendored brand tokens as Compose theme, fonts, launcher icon, network security config | Builds, installs, is visibly a PiercingXX app |
| 2 | `core/` — `Frontmatter` round-trip, `Diff3`, `ChecklistMerge` (§7.1), `Slug`, `Ulid`, `SyncPolicy`, and the full §6 table with the property test | **Every row of §6 has a named passing test, and the property test is green** |
| 3 | `data/` — Room, the `filesDir/vault` mirror, write-temp-then-rename, FTS index, VaultStore round-trip | A vault can be read, edited, and re-read with no network in sight |
| 4 | `net/` — `WebDavClient`, `PropfindParser` against real DSM output, `OneHostInterceptor`, `CredentialVault` | `curl`-equivalent behavior from the app; the one-host guard passes its whole suite |
| 5 | `sync/` — SyncEngine, Outbox, MergeEngine, WorkManager wiring, conflict forking | **The headline works: edit on the phone and in Obsidian between syncs and get a clean merge or two visible notes, never one overwritten note** |
| 6 | Setup flow — host/account/test/browse/confirm/device/first sync, including the import disclosure | A fresh install reaches a synced vault with no developer present |
| 7 | Grid + capture bar + editor — Markdown inline rendering, checklist toggling, debounced save | **Daily-driver gate: replace Keep for real, on a real vault** |
| 8 | Labels, archive, trash, search, multi-select, drawer | Keep parity complete |
| 9 | Sync screen — state, outbox with reasons, conflict Resolve sheet, log, tallies, Test connection | R10 complete: every sync outcome explains itself |
| 10 | Attachments — insert, hash-address, lazy download, cache budget, orphan sweep, EXIF strip, HEIC transcode | v1 |

WS2 before everything, same reasoning as XX-Dialer: the logic that must be
correct has no Android dependencies — build it against tests first, in
isolation. WS3–5 produce a functionally complete headless sync engine with
no UI at all, which is the correct order for finding out whether the thing
works.

---

## 18. Open decisions

| # | Question | Default until overruled |
|---|---|---|
| O1 | Vault inside Synology Drive's My Drive (`/homes/xxnote/Drive/Notes`, so DSM's own versioning and the desktop client apply) or a plain shared folder (simpler WebDAV path, no Drive coupling)? Depends on WS0's answer about what the service account can see. | My Drive, for the free server-side version history |
| O2 | Keep's twelve colours as six surface tones (D12), or overrule the brand and ship real hues for the one feature where colour carries information? | **Settled — six tones.** The brand rule is older than this app. |
| O3 | Should "checked items to the bottom" rewrite the file? | **Settled — yes, the file is rewritten.** It costs one sync event per debounced save and forces item-wise checklist merging (D18, D19, §7.1) — which turns out to make concurrent tapping *safer* than the display-only alternative would have been. |
| O4 | Trash expiry at Keep's 7 days, or longer given that the vault is the operator's own disk and space is not the constraint? | 7 days, configurable |
