#!/usr/bin/env bash
#
# ws0-probe.sh — XX-Note WS0 diagnostic kit (throwaway; todo.md rule #1).
# Answers six unverified facts against the operator's DSM before the sync
# engine is written. Writes ONLY its own probe artifacts (.ws0-probe.txt,
# .xxnote/) and deletes them at the end. The password is never printed.
#
# Run:  WS0_HOST=nas.tailnet.ts.net WS0_PASS='…' ./probe/scripts/ws0-probe.sh

set -euo pipefail

WS0_HOST="${WS0_HOST:-}"
WS0_PORT="${WS0_PORT:-5006}"
WS0_USER="${WS0_USER:-xxnote}"
WS0_PASS="${WS0_PASS:-}"
WS0_BASE="${WS0_BASE:-}"

BASE_URL="https://${WS0_HOST}:${WS0_PORT}"
PROBE_NAME=".ws0-probe.txt"
TRASH_ROOT=".xxnote"
WORK_DIR="$(mktemp -d)"
VAULT=""
readonly AUTH=(-u "$WS0_USER:$WS0_PASS")
export LC_ALL=C

trap 'rm -rf "$WORK_DIR"' EXIT

# M3 — unconditional artifact cleanup. Whatever way the run ends (normal
# exit, error under set -e, interrupt, termination), the live probe file and
# its trash twin are DELETEd remotely and WORK_DIR is removed locally.
# Best-effort and silent: curl failures never mask the transcript's exit
# status, and nothing here prints the password.
PROBE_CLEANED=0
cleanup_artifacts() {
  local status="$?"
  if [[ "$PROBE_CLEANED" -eq 0 ]]; then
    PROBE_CLEANED=1
    if [[ -n "$VAULT" ]]; then
      curl -sS --max-time 15 "${AUTH[@]}" -X DELETE \
        "${BASE_URL}${VAULT}${PROBE_NAME}" >/dev/null 2>&1 || true
      curl -sS --max-time 15 "${AUTH[@]}" -X DELETE \
        "${BASE_URL}${VAULT}${TRASH_ROOT}/trash/${PROBE_NAME}" >/dev/null 2>&1 || true
    fi
    rm -rf "$WORK_DIR"
  fi
  return "$status"
}
trap cleanup_artifacts EXIT
trap 'cleanup_artifacts; trap - INT; kill -INT "$$"' INT
trap 'cleanup_artifacts; trap - TERM; kill -TERM "$$"' TERM

usage() {
  cat <<EOF
XX-Note WS0 probe — six questions the sync engine must not be built before answering.

  WS0_HOST   NAS MagicDNS name, e.g. nas.tailnet.ts.net        (required)
  WS0_PORT   WebDAV HTTPS port                                 (default: 5006)
  WS0_USER   DSM service account (D15)                         (default: xxnote)
  WS0_PASS   password for that account — via env, never argv   (required, never printed)
  WS0_BASE   vault path prefix, e.g. /home/Drive/Notes/        (default: discovered by PROBE 4)

Example:
  WS0_HOST=nas.tailnet.ts.net WS0_PASS='...' ./probe/scripts/ws0-probe.sh 2>&1 | tee ws0-transcript.txt

Notes:
  - Never run under bash -x / set -x: that would echo the password.
  - Probe order is 4,1,2,3,5,6: PROBE 4 discovers the vault path the others need.
EOF
}

die() { printf 'FATAL: %s\n' "$*" >&2; exit 1; }

section() {
  printf '\n=====================================================================\n'
  printf '%s\n' "$1"
  printf '=====================================================================\n'
}

skipped() { section "$1 — SKIPPED"; printf 'reason: %s\n\n' "$2"; }

preflight() {
  command -v curl >/dev/null || die "curl not found in PATH"
  command -v openssl >/dev/null || die "openssl not found in PATH"
  [[ -n "$WS0_HOST" ]] || { usage >&2; die "WS0_HOST is required"; }
  [[ -n "$WS0_PASS" ]] || { usage >&2; die "WS0_PASS is required"; }
}

banner() {
  cat <<EOF
=====================================================================
 XX-Note WS0 probe kit
 target    : ${BASE_URL}
 account   : ${WS0_USER} (password read from WS0_PASS, not shown)
 vault     : ${WS0_BASE:-<discovered by PROBE 4>}
 artifacts : ${PROBE_NAME}, ${TRASH_ROOT}/ (created then deleted)
=====================================================================
EOF
}

dav() {
  local method="$1" path="$2" rc=0 out=""
  shift 2
  out="$(curl -sS --max-time 15 "${AUTH[@]}" -X "$method" "$@" \
         "${BASE_URL}${path}" -w '\nHTTP %{http_code}\n')" || rc=$?
  [[ $rc -ne 0 ]] && printf '[curl exited %s — transport failure]\n' "$rc" >&2
  printf '%s\n' "$out"
  return 0
}

propfind() {
  local path="$1" depth="$2"
  dav PROPFIND "$path" -H "Depth: $depth" -H 'Content-Type: application/xml' \
      --data-binary '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>'
}

code_of() {
  printf '%s' "$1" | grep -oE 'HTTP [0-9]{3}' | tail -n1 | cut -d' ' -f2 || true
}

get_etag_header() {
  local hdr="$WORK_DIR/get.hdr" etag=""
  curl -sS --max-time 15 "${AUTH[@]}" -o /dev/null -D "$hdr" \
       "${BASE_URL}$1" >/dev/null 2>&1 || true
  etag="$(grep -i '^etag:' "$hdr" | head -n1 | cut -d' ' -f2- || true)"
  rm -f "$hdr"
  printf '%s' "${etag%%$'\r'}"
}

etags_from_body() {
  printf '%s' "$1" | tr '>' '>\n' \
    | grep -oE '<[^>]*getetag[^>]*>[^<]*</[^>]*getetag>' \
    | sed -E 's/<[^>]+>//g; s/&quot;/"/g; s/^[[:space:]]+//; s/[[:space:]]+$//' \
    | grep -v '^$' || true
}

hrefs_from_body() {
  printf '%s' "$1" | tr '>' '>\n' \
    | grep -oE '<[^>]*href[^>]*>[^<]*' \
    | sed -E 's/<[^>]+>//' || true
}

normalize_base() {
  local b="$1"
  [[ "$b" == /* ]] || b="/$b"
  printf '%s/' "${b%/}"
}

require_vault() {
  [[ -n "$VAULT" ]]
}

probe_paths() {
  section "TRANSCRIPT — PROBE 4: service-account path discovery (answers O1)"
  echo "question: which vault prefix does the '${WS0_USER}' account see over WebDAV?"
  echo "(runs first because probes 1, 2, 3 and 6 need the vault path)"
  local candidates=("/home/" "/home/Drive/" "/home/Drive/Notes/" "/Drive/" "/Notes/" "/homes/xxnote/Drive/Notes/")
  local c code hits=()
  for c in "${candidates[@]}"; do
    echo "--- PROPFIND Depth:0 on ${c}"
    local out
    out="$(propfind "$c" 0)" || true
    printf '%s\n' "$out"
    code="$(code_of "$out")"
    case "$code" in
      207) printf '>>> %s => %s (visible)\n\n' "$c" "$code"; hits+=("$c") ;;
      *)   printf '>>> %s => %s\n\n' "$c" "${code:-none}";;
    esac
  done
  if [[ -n "$WS0_BASE" ]]; then
    VAULT="$(normalize_base "$WS0_BASE")"
    echo "operator supplied WS0_BASE — using it regardless of scan: ${VAULT}"
  else
    VAULT="${hits[0]:-}"
    if [[ -n "$VAULT" ]]; then
      echo "O1 evidence: '${WS0_USER}' sees a prefix at ${VAULT} — first visible candidate chosen."
      case "$VAULT" in
        /home/*) echo "O1 lean: My Drive (/home/...) — Synology Drive versioning applies (design O1 default).";;
        *)       echo "O1 lean: plain shared folder outside My Drive — no Drive coupling.";;
      esac
    fi
  fi
  echo ""
}

probe_etags() {
  require_vault || { skipped "TRANSCRIPT — PROBE 1: ETag strength & stability" "no vault path discovered"; return 0; }
  section "TRANSCRIPT — PROBE 1: ETag strength & stability"
  echo "question: does PROPFIND return strong getetags, stable across back-to-back calls?"
  echo "--- PROPFIND Depth:1 on ${VAULT} (call 1 of 2)"
  local out1 out2
  out1="$(propfind "$VAULT" 1)" || true
  printf '%s\n' "$out1"
  echo "--- PROPFIND Depth:1 on ${VAULT} (call 2 of 2, status only)"
  out2="$(propfind "$VAULT" 1)" || true
  printf '%s\n' "$(printf '%s' "$out2" | grep -E '^HTTP [0-9]{3}$' || true)"
  etags_from_body "$out1" > "$WORK_DIR/e1.txt"
  etags_from_body "$out2" > "$WORK_DIR/e2.txt"
  sort -u "$WORK_DIR/e1.txt" > "$WORK_DIR/e1.sorted"
  sort -u "$WORK_DIR/e2.txt" > "$WORK_DIR/e2.sorted"
  local total weak
  total="$(grep -c . "$WORK_DIR/e1.sorted" || true)"
  weak="$(grep -c '^W/' "$WORK_DIR/e1.sorted" || true)"
  echo "--- extracted getetag values (${total} unique):"
  cat "$WORK_DIR/e1.sorted"
  echo "--- weak etags (W/ prefix): ${weak}"
  if cmp -s "$WORK_DIR/e1.sorted" "$WORK_DIR/e2.sorted"; then
    echo "--- stability diff: IDENTICAL across two calls"
  else
    echo "--- stability diff: DIFFERED across two calls:"
    diff -u "$WORK_DIR/e1.sorted" "$WORK_DIR/e2.sorted" || true
  fi
  if [[ "$total" -gt 0 && "$weak" -eq 0 ]]; then
    echo "[probe 1] RESULT: strong + stable => ETag premise holds so far."
  elif [[ "$total" -eq 0 ]]; then
    echo "[probe 1] RESULT: NO etags returned — unusable; §4.2 fallback likely required."
  else
    echo "[probe 1] RESULT: weak/unstable etags present — If-Match may refuse them; §4.2 fallback likely required."
  fi
  echo ""
}

probe_if_match() {
  require_vault || { skipped "TRANSCRIPT — PROBE 2: If-Match honored on PUT?" "no vault path discovered"; return 0; }
  section "TRANSCRIPT — PROBE 2: If-Match honored on PUT?"
  echo "question: does DSM reject stale writes with 412 instead of silently overwriting?"
  local body="ws0 optimistic-concurrency probe"
  echo "--- PUT create ${VAULT}${PROBE_NAME} (expect 201)"
  local out
  out="$(dav PUT "${VAULT}${PROBE_NAME}" --data-binary "$body")" || true
  printf '%s\n' "$out"
  echo ">>> PUT create status: $(code_of "$out") (expected 201)"
  local etag
  etag="$(get_etag_header "${VAULT}${PROBE_NAME}")"
  echo "--- GET returned ETag header: ${etag:-<absent!>}"
  echo "--- PUT with CORRECT If-Match (expect 204 or 200)"
  out="$(dav PUT "${VAULT}${PROBE_NAME}" -H "If-Match: ${etag}" --data-binary "${body} v2")" || true
  printf '%s\n' "$out"
  echo ">>> correct If-Match status: $(code_of "$out") (expected 204 or 200)"
  echo "--- PUT with WRONG If-Match (expect 412 Precondition Failed)"
  out="$(dav PUT "${VAULT}${PROBE_NAME}" -H 'If-Match: "ws0-wrong-etag-000"' --data-binary "${body} v3-must-not-land")" || true
  printf '%s\n' "$out"
  echo ">>> wrong If-Match status: $(code_of "$out") (expected 412)"
  echo "[probe 2] RESULT: recorded verbatim above — 412 on wrong + 2xx on correct = lost-update protection confirmed."
  echo ""
}

probe_propfind_vs_get() {
  require_vault || { skipped "TRANSCRIPT — PROBE 3: PROPFIND vs GET etag match" "no vault path discovered"; return 0; }
  section "TRANSCRIPT — PROBE 3: PROPFIND getetag vs GET ETag header"
  echo "question: are the two identifiers the sync engine would compare actually equal?"
  local pf_out g_etag pf_etag
  echo "--- PROPFIND Depth:0 on ${VAULT}${PROBE_NAME}"
  pf_out="$(propfind "${VAULT}${PROBE_NAME}" 0)" || true
  printf '%s\n' "$pf_out"
  pf_etag="$(etags_from_body "$pf_out" | head -n1 || true)"
  g_etag="$(get_etag_header "${VAULT}${PROBE_NAME}")"
  echo "PROPFIND getetag : ${pf_etag:-<none>}"
  echo "GET ETag header  : ${g_etag:-<none>}"
  if [[ -n "$pf_etag" && "$pf_etag" == "$g_etag" ]]; then
    echo "[probe 3] RESULT: MATCH — one identifier can drive §6 rows 4/6/12."
  else
    echo "[probe 3] RESULT: MISMATCH (or absent) — engine must pick one source and treat it as canonical."
  fi
  echo ""
}

probe_cert() {
  section "TRANSCRIPT — PROBE 5: TLS certificate served on :${WS0_PORT}"
  echo "question: does the WebDAV listener serve the Tailscale/Lets Encrypt cert, not just the DSM UI?"
  echo "\$ openssl s_client -connect ${WS0_HOST}:${WS0_PORT} -servername ${WS0_HOST}"
  local cert=""
  cert="$(openssl s_client -connect "${WS0_HOST}:${WS0_PORT}" -servername "${WS0_HOST}" </dev/null 2>/dev/null \
          | openssl x509 -noout -subject -issuer -dates -ext subjectAltName 2>/dev/null || true)"
  if [[ -z "$cert" ]]; then
    cert="$(openssl s_client -connect "${WS0_HOST}:${WS0_PORT}" -servername "${WS0_HOST}" </dev/null 2>/dev/null \
            | openssl x509 -noout -text 2>/dev/null | grep -A2 -E 'Subject:|Issuer:|Not (Before|After)|Alternative' || true)"
    echo "(note: openssl lacked '-ext'; fell back to -text parse)"
  fi
  printf '%s\n' "$cert"
  echo "\$ chain verification (-verify_return_error):"
  local vrc=0 vout=""
  vout="$(openssl s_client -connect "${WS0_HOST}:${WS0_PORT}" -servername "${WS0_HOST}" \
          -verify_return_error </dev/null 2>&1)" || vrc=$?
  printf '%s\n' "$vout" | grep -E '^(depth|verify|Verify)' || true
  if [[ $vrc -eq 0 && -n "$cert" ]]; then
    echo "[probe 5] RESULT: PASS — publicly-trusted chain served on the WebDAV listener itself."
    echo "          §4.1 [VERIFY] resolves YES: system trust anchor suffices, no pinning needed."
  else
    echo "[probe 5] RESULT: FAIL (openssl rc=${vrc}) — design §15 forbids any bypass; fix DSM cert binding first."
  fi
  echo ""
}

probe_trash() {
  require_vault || { skipped "TRANSCRIPT — PROBE 6: trash folder semantics" "no vault path discovered"; return 0; }
  section "TRANSCRIPT — PROBE 6: trash folder semantics smoke test"
  echo "question: does MOVE into ${TRASH_ROOT}/trash/ give D9's delete-with-tombstone shape?"
  local out
  echo "--- MKCOL ${VAULT}${TRASH_ROOT}/ (expect 201; 405 = already existed)"
  out="$(dav MKCOL "${VAULT}${TRASH_ROOT}/")" || true
  printf '%s\n' "$out"
  echo ">>> MKCOL ${TRASH_ROOT}: $(code_of "$out")"
  echo "--- MKCOL ${VAULT}${TRASH_ROOT}/trash/ (expect 201; 405 = already existed)"
  out="$(dav MKCOL "${VAULT}${TRASH_ROOT}/trash/")" || true
  printf '%s\n' "$out"
  echo ">>> MKCOL ${TRASH_ROOT}/trash: $(code_of "$out")"
  echo "--- MOVE ${VAULT}${PROBE_NAME} -> trash/ (expect 201 Created)"
  out="$(dav MOVE "${VAULT}${PROBE_NAME}" \
              -H "Destination: ${BASE_URL}${VAULT}${TRASH_ROOT}/trash/${PROBE_NAME}" \
              -H 'Overwrite: T')" || true
  printf '%s\n' "$out"
  move_code="$(code_of "$out")"
  echo ">>> MOVE to trash: ${move_code:-none} (expected 201)"
  # A failed MOVE must never abort the run mid-state (M3): the status is
  # recorded above and the transcript continues to the listings and to the
  # end-of-run cleanup — the EXIT/INT/TERM trap deletes any stray probe file
  # regardless of what this MOVE returned.
  echo "--- live listing must no longer contain the probe file"
  out="$(propfind "$VAULT" 1)" || true
  printf '%s\n' "$out"
  local live_hits
  live_hits="$(hrefs_from_body "$out" | grep -F "$PROBE_NAME" | grep -cv "${TRASH_ROOT}" || true)"
  if [[ "$live_hits" -eq 0 ]]; then echo ">>> vanished from live listing: YES"; else echo ">>> vanished from live listing: NO (${live_hits} hits)"; fi
  echo "--- trash listing must contain it"
  out="$(propfind "${VAULT}${TRASH_ROOT}/trash/" 1)" || true
  printf '%s\n' "$out"
  local trash_hits
  trash_hits="$(hrefs_from_body "$out" | grep -cF "$PROBE_NAME" || true)"
  if [[ "$trash_hits" -gt 0 ]]; then echo ">>> present under trash/: YES"; else echo ">>> present under trash/: NO — check MOVE status above"; fi
  echo "--- cleanup: DELETE trashed file, then DELETE ${TRASH_ROOT}/ recursively (expect 204 each)"
  out="$(dav DELETE "${VAULT}${TRASH_ROOT}/trash/${PROBE_NAME}")" || true
  printf '%s\n' "$out"
  echo ">>> DELETE file: $(code_of "$out")"
  out="$(dav DELETE "${VAULT}${TRASH_ROOT}/")" || true
  printf '%s\n' "$out"
  echo ">>> DELETE ${TRASH_ROOT}/ tree: $(code_of "$out")"
  echo "[probe 6] RESULT: statuses verbatim above — 201 MOVE + absent-live + present-trash = D9 works as designed."
  echo ""
}

footer() {
  section "WS0 probe complete"
  echo "Paste each TRANSCRIPT block into probe/results-template.md, answer, then transcribe into"
  echo "design.md §4 replacing the [VERIFY] flags. Decide O1 and the ETags-USABLE gate there."
  echo "Artifacts left behind: any listed above (cleanup ran unless a DELETE failed)."
}

main() {
  preflight
  banner
  probe_paths
  probe_etags
  probe_if_match
  probe_propfind_vs_get
  probe_cert
  probe_trash
  footer
}

main "$@"
