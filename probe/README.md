# WS0 Probe Kit

Throwaway diagnostics for todo.md rule #1: answer six unverified facts
**before** the sync engine is built. Nothing here is shipped; once the
answers are transcribed into `design.md` §4, delete this directory.

The six questions:

| # | Question | Design ref |
|---|---|---|
| 1 | Strong, stable `getetag` in PROPFIND? | §4.2 [VERIFY] ETags |
| 2 | `PUT` honors `If-Match` (2xx correct / 412 wrong)? | §4.2, §6 rows 4/6/12 |
| 3 | PROPFIND `getetag` == GET `ETag` header? | §4.2 |
| 4 | What path does the `xxnote` account see the vault at? (decides O1) | §4.2 path prefix, D15 |
| 5 | Is the Tailscale cert served on :5006 itself? | §4.1 |
| 6 | Trash semantics via MKCOL/MOVE/DELETE work as D9 assumes? | D9 |

---

## Half 1 — WebDAV script (run from any machine on the tailnet)

Needs only `curl` + `openssl`. Configure entirely by env vars:

```sh
export WS0_HOST=nas.yourtailnet.ts.net   # required — MagicDNS name
export WS0_PORT=5006                     # default
export WS0_USER=xxnote                   # default (D15 service account)
read -rs WS0_PASS && export WS0_PASS     # keeps the password off screen and out of argv
# export WS0_BASE=/home/Drive/Notes/     # optional — otherwise discovered by PROBE 4

bash probe/scripts/ws0-probe.sh 2>&1 | tee ws0-transcript.txt
```

Notes:

- Run it from a laptop or desktop already joined to the tailnet; the NAS must
  be reachable over MagicDNS with the WebDAV Server package enabled on HTTPS.
- The script never prints the password. **Do not run it under `bash -x` /
  `set -x`** — that would echo it.
- It writes only its own artifacts (`.ws0-probe.txt`, `.xxnote/`) inside the
  vault and deletes them at the end.
- Probe order is **4, 1, 2, 3, 5, 6**: PROBE 4 discovers the vault path the
  others need when `WS0_BASE` is unset.

## Half 2 — StrongBox APK (install onto `caiman`)

`probe/src/main/java/com/piercingxx/xxnote/probe/ProbeActivity.kt` mirrors
§4.5's [VERIFY]: generate an AES-GCM key requesting StrongBox, catch
`StrongBoxUnavailableException`, retry without it, seal + unseal 32 bytes,
print a verdict.

Build & install (the repo's gradle build is owned elsewhere right now — drop
this file into any scratch app module, minSdk 31 / compileSdk 34+, zero
dependencies, with a manifest `<activity>` entry for it):

```sh
adb install -r ws0-probe.apk
adb shell am start -n com.piercingxx.xxnote.probe/com.piercingxx.xxnote.probe.ProbeActivity
```

Expected lines on the device screen:

```
StrongBox: available            (or: unavailable(fallback))
seal: ok
unseal: ok
VERDICT: PASS — AES-GCM seal/unseal round-trips in StrongBox
```

No permissions are requested; GrapheneOS will not prompt.

## Recording answers

1. Paste each script `TRANSCRIPT` block into the matching section of
   [`probe/results-template.md`](results-template.md), fill its ANSWER line.
2. Decide the two boxes at the bottom of that file:
   - **O1 DECISION** — My Drive vs plain shared folder;
   - **ETags-USABLE gate** — if NO, design §4.2's fallback is adopted
     *before* the engine is written (todo.md rules #1).
3. Transcribe into `design.md` §4.1 / §4.2 / §4.5 replacing the **[VERIFY]**
   flags, record O1 in todo.md ("Decisions still open" → settled) and
   design.md §18.
