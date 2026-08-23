# XX-Note

The notes app that saves nothing but Markdown.

Google Keep's front end — card grid, one-tap capture, checklists, labels,
pin, archive, search — over a folder of `.md` files on your own Synology,
reached over Tailscale. Every note is one plain file with YAML frontmatter.
Open the vault in Obsidian, in `vim`, in Notepad, in ten years. Delete this
app and lose nothing.

**Status:** specification only. Nothing built.
**Spec:** [design.md](design.md) — the full design.
**Build plan:** [todo.md](todo.md) — workstreams, gates, and the order to do them in.
**Screens:** [design/xx-note-screens.html](design/xx-note-screens.html) — the mockup.
**Research:** [design/research.md](design/research.md) — sourced findings behind this spec.
**Target:** Pixel 9 Pro (`caiman`), GrapheneOS, Android 17 / SDK 37.

---

## What a note is

```markdown
---
id: 01J9F2K3M4N5P6Q7R8S9T0V1W2
title: Grocery list
created: 2026-08-23T10:04:12Z
updated: 2026-08-23T10:07:55Z
pinned: true
labels: [home, errands]
type: checklist
---

- [ ] oat milk
- [ ] coffee, the dark one
- [x] bin bags
```

That is the whole storage format. The filename is `<ulid>-<slug>.md` and is
cosmetic — identity is the `id`, so a note renamed in Obsidian is still the
same note. Attachments sit in `attachments/` under their own content hash
and are referenced by ordinary relative Markdown links.

## The sync rule

Sync is a truth table over three snapshots — what the server had at the last
agreement, what the phone has now, what the server has now — evaluated by a
pure function with no Android dependencies. First match wins.

| Local | Remote | What happens |
|---|---|---|
| clean | clean | nothing |
| edited | clean | push |
| clean | edited | pull |
| edited | edited | three-way merge; unmergeable → **two visible notes** |
| trashed | edited | the edit wins, the note comes back |
| edited | deleted | the edit wins, the note comes back |
| deleted | clean | moved to trash, never unlinked |

One invariant governs all of it, and it is tested as a property over every
row: **no sync outcome reduces the bytes you can still read.** Deletes
become trash. Conflicts become two notes named the way Synology Drive names
its own. There is no timestamp-based resolution anywhere in the app — not as
a fallback, not as a setting — because "newest wins" is how sync engines
lose an afternoon of writing without telling anyone.

## About the network permission

The rest of the family makes a hard claim: no `INTERNET` permission, and you
can check the manifest yourself. **XX-Note cannot make that claim** — talking
to your NAS requires the permission, and pretending otherwise would be the
first dishonest thing in this repo.

The honest version: one host, no third party. The app reaches exactly one
origin — your Synology at its Tailscale address — enforced by an HTTP
interceptor that throws on any other host, a network security config that
trusts only that domain and forbids cleartext, and a dependency allowlist in
CI. No analytics, no crash reporting, no ads, no Play Services, no Firebase.
On GrapheneOS the Network permission is visible and revocable, and revoking
it leaves you a fully working local notes app that says so.

## What is not in v1

Reminders, the home-screen widget, the quick-settings tile, and
share-to-note are deferred with intent — the frontmatter reserves
`reminder:` so today's vaults stay forward-compatible. Collaboration,
drawings, rich text beyond Markdown, cloud accounts of any kind, and
end-to-end encryption of the vault are permanent non-goals, with reasons, in
[design.md §2](design.md).

## Family

Brand, tokens, and type come from
[piercingxx-branding](https://github.com/PiercingXX/piercingxx-branding).
AMOLED black, Signal white, Space Mono / JetBrains Mono. Same stack and
conventions as [XX-Phone](https://github.com/PiercingXX/xx-phone),
[Nope-Mode](https://github.com/PiercingXX/Nope-Mode), and the Launcher —
Compose here rather than Views, by the XX-Vitals precedent, because a live
Markdown editor is the case that argues for it.

Free and ad-free. Collects no personal data. Nothing this app sees goes
anywhere but your own NAS.
