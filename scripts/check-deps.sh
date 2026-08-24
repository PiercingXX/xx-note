#!/usr/bin/env bash
# Hardening #13 / standing rule R8 ("one host means one host"): the release
# runtime classpath is audited for anything that talks to a network on its own
# — analytics, crash reporting, Play Services, Firebase, gRPC. Every resolved
# external group:artifact must appear on the pinned allowlist below. A new
# dependency fails the build until someone adds it here deliberately, with a
# reason next to the entry. Allowlist derived verbatim from the actual
# `:app:dependencies --configuration releaseRuntimeClasspath` resolution.
set -euo pipefail
cd "$(dirname "$0")/.."

DEPS=$(mktemp)
trap 'rm -f "$DEPS"' EXIT
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --console=plain >"$DEPS"

RESOLVED=$(grep -oE '[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[a-zA-Z0-9._+-]+' "$DEPS" |
    cut -d: -f1-2 | sort -u)
printf '%s\n' "$RESOLVED" >"$DEPS"

# Pinned allowlist (sorted). Declared roots: compose BOM + ui/material3,
# activity-compose, core-ktx, lifecycle (runtime-ktx/runtime-compose/
# viewmodel-compose), navigation-compose, room (runtime/ktx), work-runtime-ktx,
# okhttp, project :core. Everything else is their transitive closure.
ALLOWLIST=$(cat <<'EOF'
androidx.activity:activity
androidx.activity:activity-compose
androidx.activity:activity-ktx
androidx.annotation:annotation
androidx.annotation:annotation-experimental
androidx.annotation:annotation-jvm
androidx.arch.core:core-common
androidx.arch.core:core-runtime
androidx.autofill:autofill
androidx.collection:collection
androidx.collection:collection-jvm
androidx.collection:collection-ktx
androidx.compose.animation:animation
androidx.compose.animation:animation-android
androidx.compose.animation:animation-core
androidx.compose.animation:animation-core-android
androidx.compose:compose-bom
androidx.compose.foundation:foundation
androidx.compose.foundation:foundation-android
androidx.compose.foundation:foundation-layout
androidx.compose.foundation:foundation-layout-android
androidx.compose.material3:material3
androidx.compose.material3:material3-android
androidx.compose.material:material-icons-core
androidx.compose.material:material-icons-core-android
androidx.compose.material:material-ripple
androidx.compose.material:material-ripple-android
androidx.compose.runtime:runtime
androidx.compose.runtime:runtime-android
androidx.compose.runtime:runtime-annotation
androidx.compose.runtime:runtime-annotation-android
androidx.compose.runtime:runtime-saveable
androidx.compose.runtime:runtime-saveable-android
androidx.compose.ui:ui
androidx.compose.ui:ui-android
androidx.compose.ui:ui-geometry
androidx.compose.ui:ui-geometry-android
androidx.compose.ui:ui-graphics
androidx.compose.ui:ui-graphics-android
androidx.compose.ui:ui-text
androidx.compose.ui:ui-text-android
androidx.compose.ui:ui-unit
androidx.compose.ui:ui-unit-android
androidx.compose.ui:ui-util
androidx.compose.ui:ui-util-android
androidx.concurrent:concurrent-futures
androidx.concurrent:concurrent-futures-ktx
androidx.core:core
androidx.core:core-ktx
androidx.core:core-viewtree
androidx.customview:customview-poolingcontainer
androidx.emoji2:emoji2
androidx.graphics:graphics-path
androidx.interpolator:interpolator
androidx.lifecycle:lifecycle-common
androidx.lifecycle:lifecycle-common-java8
androidx.lifecycle:lifecycle-common-jvm
androidx.lifecycle:lifecycle-livedata
androidx.lifecycle:lifecycle-livedata-core
androidx.lifecycle:lifecycle-livedata-core-ktx
androidx.lifecycle:lifecycle-process
androidx.lifecycle:lifecycle-runtime
androidx.lifecycle:lifecycle-runtime-android
androidx.lifecycle:lifecycle-runtime-compose
androidx.lifecycle:lifecycle-runtime-compose-android
androidx.lifecycle:lifecycle-runtime-ktx
androidx.lifecycle:lifecycle-runtime-ktx-android
androidx.lifecycle:lifecycle-service
androidx.lifecycle:lifecycle-viewmodel
androidx.lifecycle:lifecycle-viewmodel-android
androidx.lifecycle:lifecycle-viewmodel-compose
androidx.lifecycle:lifecycle-viewmodel-compose-android
androidx.lifecycle:lifecycle-viewmodel-ktx
androidx.lifecycle:lifecycle-viewmodel-savedstate
androidx.lifecycle:lifecycle-viewmodel-savedstate-android
androidx.navigation:navigation-common
androidx.navigation:navigation-common-android
androidx.navigation:navigation-compose
androidx.navigation:navigation-compose-android
androidx.navigation:navigation-runtime
androidx.navigation:navigation-runtime-android
androidx.profileinstaller:profileinstaller
androidx.room:room-common
androidx.room:room-common-jvm
androidx.room:room-ktx
androidx.room:room-runtime
androidx.room:room-runtime-android
androidx.savedstate:savedstate
androidx.savedstate:savedstate-android
androidx.savedstate:savedstate-compose
androidx.savedstate:savedstate-compose-android
androidx.savedstate:savedstate-ktx
androidx.sqlite:sqlite
androidx.sqlite:sqlite-android
androidx.sqlite:sqlite-framework
androidx.sqlite:sqlite-framework-android
androidx.startup:startup-runtime
androidx.tracing:tracing
androidx.tracing:tracing-ktx
androidx.versionedparcelable:versionedparcelable
androidx.work:work-runtime
androidx.work:work-runtime-ktx
com.google.guava:listenablefuture
com.squareup.okhttp3:okhttp
com.squareup.okio:okio
com.squareup.okio:okio-jvm
org.jetbrains:annotations
org.jetbrains.kotlin:kotlin-stdlib
org.jetbrains.kotlin:kotlin-stdlib-common
org.jetbrains.kotlin:kotlin-stdlib-jdk7
org.jetbrains.kotlin:kotlin-stdlib-jdk8
org.jetbrains.kotlinx:kotlinx-coroutines-android
org.jetbrains.kotlinx:kotlinx-coroutines-bom
org.jetbrains.kotlinx:kotlinx-coroutines-core
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm
org.jetbrains.kotlinx:kotlinx-serialization-bom
org.jetbrains.kotlinx:kotlinx-serialization-core
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm
org.jspecify:jspecify
EOF
)

# Named offenders first — glob patterns, matched per resolved line.
while IFS= read -r ARTIFACT; do
    case "$ARTIFACT" in
        com.google.android.gms* | com.google.firebase* | com.google.android.play* | io.grpc* | io.sentry* | io.crashlytics*)
            echo "check-deps: FORBIDDEN DEPENDENCY RESOLVED ON RELEASE CLASSPATH: $ARTIFACT" >&2
            echo "  (network-capable third party — R8 forbids analytics/crash/" >&2
            echo "   Play Services/Firebase/gRPC outright)" >&2
            exit 1
            ;;
    esac
done <"$DEPS"

EXTRA=$(comm -13 <(printf '%s\n' "$ALLOWLIST") "$DEPS")
if [ -n "$EXTRA" ]; then
    echo "check-deps: UNLISTED DEPENDENCIES ON RELEASE RUNTIME CLASSPATH:" >&2
    printf '%s\n' "$EXTRA" >&2
    echo "Add to scripts/check-deps.sh only after auditing what it talks to." >&2
    exit 1
fi

# Allowlist entries that no longer resolve are not failures, but they rot;
# name them so someone prunes them.
comm -23 <(printf '%s\n' "$ALLOWLIST") "$DEPS" | while read -r STALE; do
    echo "check-deps: note: allowlist entry no longer resolved: $STALE" >&2
done

COUNT=$(wc -l <"$DEPS" | tr -d ' ')
echo "check-deps: OK — $COUNT resolved group:artifact pairs all allowlisted;"
echo "no network-capable third party in the tree."
