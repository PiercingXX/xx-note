# `core/` — the pure-JVM policy core

Everything in this module imports nothing from `android.*` and carries the
correctness burden (todo rule #5, design §5/§14). It is proven by plain JVM
tests: `./gradlew :core:test`.

## Pinned contracts (do not rename)

- `Verdict` — the §6 outcomes: Pull · Push · Merge · Fork · Trash ·
  Resurrect · Nothing · Replan.
- `NoteState(body, trashed)` — one side's view; `body == null` means absent.
- `BaseSnapshot(body, etag)` — last-agreed snapshot (D7); null = never synced.

## SyncPolicy (WS2)

```kotlin
object SyncPolicy {
    fun decide(base: BaseSnapshot?, local: NoteState, remote: NoteState): Verdict
    fun decidePushRejection(roundsCompleted: Int): Verdict   // <3 → Replan, else Fork
}
```

Totality rules for combinations the §6 table does not list — every unlisted
corner resolves toward preservation, never deletion:

- both sides absent → `Nothing`
- no base, local trashed, remote present → `Fork` (two claims on one id)
- no base, both present, equal bodies → `Nothing` (engine adopts as base)
- local trashed, remote gone, base existed → `Nothing`
- a remote note in `.xxnote/trash/` counts as **gone** from the live vault;
  `remote.trashed` mirrors that when a trash listing is available
- "dirty" means body differs from base body (string equality here; hashing
  is the engine's business). `body` is WHOLE-FILE text — YAML frontmatter
  block plus Markdown body, exactly the bytes of the .md file — so a
  labels/pin/color-only edit dirties the snapshot and propagates instead of
  evaluating clean+clean → `Nothing`.

## MergeEngine inputs (WS2 pieces, WS5 assembly)

- `Diff3.merge(base, a, b)` — line-based; refusal is a first-class outcome.
- `ChecklistMerge.merge(base, local, remote)` — item-wise per §7.1; returns
  merged body or Fork. Never reorders on merge (D19): sort-to-bottom is an
  editor save-time behavior only.
- `Frontmatter.parse(raw).raw() == raw` byte-exact for every input, and an
  empty mutation is a strict no-op (`Frontmatter.parse(raw).rewritten {} ==
  raw`) — including malformed blocks (which degrade to body text).

## Known limitation (documented, deliberate)

Block-style YAML (indented lists under a key) degrades the whole frontmatter
block to body text — letter-of-spec compliant (nothing discarded), flagged
here because common hand-written Obsidian vaults use that shape; revisit only
with operator evidence from WS0/WS6 imports.

## House rules

- No external dependencies. JDK + kotlin-stdlib only.
- Deterministic tests: seeded randomness only, no wall-clock assertions on
  generated ids beyond format/sortability.
- A workstream with failing tests is not done.
