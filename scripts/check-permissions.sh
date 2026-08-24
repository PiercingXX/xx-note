#!/usr/bin/env bash
# Hardening #13 / todo.md standing rule: `aapt2 dump permissions` at every WS
# exit shows INTERNET, ACCESS_NETWORK_STATE, CAMERA, POST_NOTIFICATIONS — and
# nothing else rots silently. This automates it against the MERGED release
# manifest: the declared four, WAKE_LOCK + RECEIVE_BOOT_COMPLETED merged in
# from WorkManager (justified in AndroidManifest.xml), and androidx.core's
# DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, which the app must both declare
# (signature-protected) and self-grant. Any other entry — or a lost entry —
# fails until the pinned list below is edited deliberately, in the open.
set -euo pipefail
cd "$(dirname "$0")/.."

MERGED=$(find app/build/intermediates/merged_manifests/release \
    -name AndroidManifest.xml -print -quit 2>/dev/null || true)
if [ -z "${MERGED:-}" ]; then
    echo "check-permissions: no merged release manifest found under" >&2
    echo "  app/build/intermediates/merged_manifests/release/" >&2
    echo "Run './gradlew :app:assembleRelease' first." >&2
    exit 1
fi
echo "check-permissions: auditing $MERGED"

# Pinned expected uses-permission set (sorted).
EXPECTED=$(cat <<'EOF'
android.permission.ACCESS_NETWORK_STATE
android.permission.CAMERA
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
com.piercingxx.xxnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
EOF
)

ACTUAL=$(grep -o '<uses-permission android:name="[^"]*"' "$MERGED" |
    sed 's/<uses-permission android:name="//; s/"$//' | sort)

echo "--- expected vs actual (uses-permission) ---"
if DIFF=$(diff <(printf '%s\n' "$EXPECTED") <(printf '%s\n' "$ACTUAL")); then
    echo "(identical)"
else
    echo "check-permissions: PERMISSION SET MISMATCH" >&2
    printf '%s\n' "$DIFF" >&2
    echo "< = pinned expected   > = found in merged manifest" >&2
    exit 1
fi

# Second element spelling: <uses-permission-sdk-23> is invisible to the grep
# above (the literal space after "uses-permission" never matches "-sdk-23"),
# so it gets its own extraction and its own pinned set — currently empty;
# edit SDK23_EXPECTED deliberately, in the open, if one is ever needed.
SDK23_EXPECTED=""

# No match is the expected case (grep exits 1) — an empty set, not an error.
ACTUAL_SDK23=$(grep -o '<uses-permission-sdk-23 android:name="[^"]*"' "$MERGED" |
    sed 's/<uses-permission-sdk-23 android:name="//; s/"$//' | sort || true)

echo "--- expected vs actual (uses-permission-sdk-23) ---"
if DIFF=$(diff <(printf '%s' "$SDK23_EXPECTED") <(printf '%s' "$ACTUAL_SDK23")); then
    echo "(identical)"
else
    echo "check-permissions: USES-PERMISSION-SDK-23 SET MISMATCH" >&2
    printf '%s\n' "$DIFF" >&2
    echo "< = pinned expected   > = found in merged manifest" >&2
    exit 1
fi

# The custom permission must also exist as a declaration, signature-guarded,
# or androidx.core's dynamic-receiver guard loses its protection.
FLAT=$(tr '\n' ' ' < "$MERGED" | tr -s ' ')
if ! printf '%s' "$FLAT" | grep -q \
'<permission android:name="com.piercingxx.xxnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature"'; then
    echo "check-permissions: MISSING custom <permission> declaration for" >&2
    echo "  com.piercingxx.xxnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" >&2
    echo "  (must carry android:protectionLevel=\"signature\")" >&2
    exit 1
fi

echo "check-permissions: OK — 7 uses-permissions match the pinned list;"
echo "uses-permission-sdk-23 set matches its pinned (empty) list;"
echo "custom dynamic-receiver permission declared with signature protection."
