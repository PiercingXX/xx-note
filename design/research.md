# XX-Note — Research Notes

Findings behind [design.md](../design.md), from one research pass
(2026-08-23): the Synology/WebDAV/Tailscale transport chain, Android's
background-execution rules, and the notes-app landscape (listings, help
pages, licence files — **no copyleft source opened**, per the cleanroom rule
in design §1).

This pass is **narrower than XX-Dialer's**. That spec could source almost
everything from AOSP; this one leans on the operator's specific NAS, whose
behavior is not documented anywhere and is WS0's job to measure. Items below
marked **[VERIFY]** are open questions, not findings — do not build on them.

---

## Transport (source-verified)

- **DSM WebDAV is a separate package**, not a DSM built-in — installed from
  Package Center, configured at Settings → HTTP/HTTPS. Default ports
  **5005 HTTP / 5006 HTTPS**; HTTPS is off until explicitly enabled.
  Synology's own guidance is to use HTTPS. Design §4.2 forbids 5005
  outright via the network security config, so a misconfiguration fails
  closed rather than silently sending Basic-auth credentials in the clear.
  https://kb.synology.com/en-global/DSM/help/WebDAVServer/webdav_server
- **Synology Drive's "My Drive" is `/homes/<user>/Drive`** on the
  filesystem — a directory inside the user's home share, not a share of its
  own. This is what makes O1 a real decision: a vault placed there inherits
  Synology Drive's server-side version history and desktop-client sync for
  free, but a *service* account (design D15) may not have a Drive of its own
  to place it in.
  https://kb.synology.com/en-us/DSM/tutorial/Drive_difference_between_homes_My_Drive_home_folders
- **Synology Drive's conflict-file naming** is
  `<name>_<client>_<Mon-DD-HHMM-YYYY>_<reason>_<n>.<ext>` — e.g.
  `a_Andy-PC_Jan-03-0901-2013_CaseConflict_1.txt`. The client name is
  per-device by design. Design D8 imitates this deliberately so an XX-Note
  fork reads as native in Drive's own web UI rather than introducing a third
  vocabulary into a folder that already has one.
  https://kb.synology.com/en-us/DSM/tutorial/Synology_Drive_file_conflict_resolution
- **WebDAV ETag semantics** (RFC 4918): all DAV-compliant resources must
  support `PROPFIND`, and `DAV:getetag` must follow HTTP entity-tag
  semantics. Clients avoid lost updates with `If-Match` on modifying
  requests. Two documented hazards apply directly to design §4.2: **weak
  ETags cannot be used in `If-Match`**, and the `ETag` header on `GET` is
  not guaranteed to equal the `getetag` property from `PROPFIND` — a real
  interop gap, observed in the wild in other servers.
  https://datatracker.ietf.org/doc/html/rfc4918
  https://redmine.lighttpd.net/boards/3/topics/7473
- **[VERIFY] Synology's WebDAV ETag behavior specifically.** No Synology
  documentation states whether its `getetag` is strong, whether it is stable
  across reads, or whether `PUT` honors `If-Match` at all. Nothing was found
  either way. This is the single highest-risk unknown in the design and it
  is WS0's first question; §4.2 carries the fallback if the answer is bad.

## Tailscale on Android (source-verified)

- Tailscale is an ordinary app holding the system `VpnService`. When it is
  up, MagicDNS names resolve device-wide and any app's sockets route to the
  tailnet — **XX-Note needs no Tailscale integration, SDK, or awareness**
  (design D5). Android permits one active VPN, so another VPN means
  Tailscale is down and XX-Note is offline.
- **App-based split tunnelling** (Tailscale 1.70+) lets the operator include
  or exclude specific apps from the tailnet. XX-Note must behave correctly
  on the excluded side, which is indistinguishable from the tailnet being
  down — hence the single "unreachable" state in §15 rather than three
  states the user would have to tell apart.
  https://tailscale.com/docs/features/client/android-app-split-tunneling
- **MagicDNS lookups for tailnet names never leave the device**, which is a
  small but real privacy property worth stating: XX-Note's DNS traffic
  discloses nothing to a resolver.
- **[VERIFY] the certificate on port 5006.** Tailscale can provision real
  Let's Encrypt certificates for `*.ts.net` MagicDNS names, which would give
  XX-Note valid TLS with no pinning and no user-installed CA. But DSM binds
  certificates *per service*, and the certificate serving the DSM web UI is
  not necessarily the one serving the WebDAV listener. WS0 checks 5006
  specifically. Design §15 forbids any "trust anyway" affordance, so this
  must be right before WS4.

## Android background execution (source-verified)

- **`dataSync` foreground services are capped** at 6 hours per 24 for apps
  targeting API 35+; past the budget the system throws
  `ForegroundServiceStartNotAllowedException` unless the user brings the app
  to the foreground. On **Android 16**, background jobs started *from* a
  foreground service obey their own runtime quotas anyway. Conclusion for
  design §4.4: a notes app has no business holding that budget — sync is
  WorkManager, not a foreground service.
  https://developer.android.com/about/versions/15/changes/datasync-migration
  https://developer.android.com/develop/background-work/services/fgs/timeout
- **User-initiated data transfer jobs** are the documented exemption from
  ordinary job quotas for transfers the user asked for. That is exactly the
  shape of "Sync everything now" over a large vault, and is the only place
  design §4.4 uses it.
  https://developer.android.com/develop/background-work/services/fgs/changes
- WorkManager supports work past 10 minutes only by starting a foreground
  service, inheriting the same restrictions; jobs over 10 minutes get
  rescheduled. Periodic work has a 15-minute floor and Doze defers it.
  Design §4.4 states this plainly rather than fighting it: XX-Note syncs on
  foreground, on save, on refresh, and opportunistically — it is not a
  real-time sync engine and does not imply that it is.

## The notes-app landscape (licences verified, no source opened)

| Project | Licence | Consultable? |
|---|---|---|
| Markor | **Apache-2.0** (translations CC0-1.0) | Docs and behavior only. Apache-2.0 carries NOTICE obligations that do not belong in an all-rights-reserved repo — treated exactly as XX-Dialer treats AOSP. https://github.com/gsantner/markor |
| Joplin | **AGPL-3.0** (+ a separate server licence) | **Never opened.** Doubly radioactive. https://github.com/laurent22/joplin |
| Obsidian | **proprietary** — the core app is fully closed; only the API, the importer, and a few tools are open | Published vault-format documentation only. That documentation is the interop target for design §8, which is what interoperability requires. https://en.wikipedia.org/wiki/Obsidian_(software) |
| Fossify Notes / Notally | GPL-3.0 | **Never opened.** |
| Standard Notes | AGPL-3.0 | **Never opened.** |
| Google Keep | proprietary | Published screenshots, help pages, and fifteen years of using it. The whole front-end IA in design §12. |
| Syncthing | MPL-2.0 | Docs only — the conflict-copy convention, not the implementation. |

The gap the product sits in: Markor proves a files-first Markdown notes app
works on Android and lacks Keep's grid; Keep has the grid and owns your
data; Obsidian has the vault and is a desktop-shaped editor with a mobile
port. Nobody ships Keep's capture surface over a vault you own on hardware
you own.

---

## What this pass did not answer

Everything in the **[VERIFY]** list above, plus:

- Whether DSM's WebDAV `MOVE` preserves ETags across a rename (matters for
  §9 — a retitle that changes the ETag looks like a remote edit to the next
  sync, which would fork spuriously). WS0 should measure this too; it was
  not in the original probe list and belongs there.
- DSM's `PROPFIND` response size and latency over Tailscale for a vault of
  a few hundred notes — the polling cost model in §4.4 assumes it is cheap
  and has not measured it.
- Whether DSM enforces any rate limit on Basic-auth requests that a
  15-minute poll could trip.

None of these block WS1–3. All of them block WS4.
