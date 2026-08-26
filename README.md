# XX-Note

> Delete this app and lose nothing — the notes were plain Markdown the whole time.

Google Keep's front end — card grid, one-tap capture, checklists, labels, pin,
archive, search — over a folder of `.md` files on hardware you own. Every note
is one plain file with YAML frontmatter, so the vault opens in Obsidian, in
`vim`, in Notepad, in ten years.

<img src="docs/images/screenshot.png" width="270" alt="XX-Note on a Pixel 6, AMOLED Night">


## A note, whole

```markdown
---
id: 01J9F2K3M4N5P6Q7R8S9T0V1W2
title: Grocery list
pinned: true
labels: [home, errands]
type: checklist
---

- [ ] oat milk
- [x] bin bags
```

That is the entire storage format. The filename is cosmetic; `id:` is the
identity, so a note renamed in Obsidian is still the same note. Attachments sit
in `attachments/` under a content hash, as relative Markdown links.

## Sync ⚙️

Three-way merge against a stored base snapshot. Deletes become trash, conflicts
become two visible notes, and there is no timestamp resolution anywhere — not as
a fallback, not as a setting — because "newest wins" is how sync engines lose an
afternoon of writing without telling anyone. One invariant, tested as a property
over every row of the table in [design.md §6](design.md): no sync outcome
reduces the bytes you can still read.

Transport is WebDAV to a Synology over Tailscale, behind a `RemoteFiles` port.
[`server/`](server/README.md) holds `xxnote-server`, a static Go binary serving
the same vault over estate fabric tokens. It mirrors the port 1:1, unwired.

## The network permission

The rest of the family declares no `INTERNET`. XX-Note cannot — reaching your
own server requires it, and pretending otherwise would be the first dishonest
thing in this repo. The honest version: one host, no third party.
`OneHostInterceptor` throws before a socket opens for anything but the
configured origin, cleartext is forbidden everywhere, and CI fails the build on
permission drift. Revoke Network on GrapheneOS and you still have a working
local notes app that says so.

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=$HOME/tools/jdk-21.0.12.1+1
./gradlew testDebugUnitTest :core:test   # 623 green, 1 deliberate skip
./gradlew :app:assembleRelease           # 3.8 MB, R8 on, unsigned
cd server && go test ./...               # 19 green, stdlib only
```

SDK 35, `minSdk 31`. Test device is a Pixel 6 on GrapheneOS.

## Status 🧪

**It installs, launches, and draws its setup screen. That is the entire list of
what is proven on a phone.** Sync has never run against a real server from a
device: no notes in the database, no credentials ever entered. Everything above
is proven by tests on a JVM, not by a round trip to a NAS. Treat the
server-backed half as unverified.

Reminders, widget, tile and share-to-note are v2, with `reminder:` reserved in
frontmatter so today's vaults stay compatible. Collaboration, drawings, rich
text and cloud accounts are permanent non-goals, with reasons, in
[design.md §2](design.md).

## More

[design.md](design.md) is the spec, [todo.md](todo.md) the build plan,
[todo-hardening.md](todo-hardening.md) the gap between "tests are green" and
"trust it with the only copy of your writing". Brand from
[piercingxx-branding](https://github.com/PiercingXX/piercingxx-branding); set
the theme once in XX-Launcher, the estate follows.

Free and ad-free. Collects no personal data. Nothing this app sees goes anywhere
but your own server. [LICENSE](LICENSE) — all rights reserved.
