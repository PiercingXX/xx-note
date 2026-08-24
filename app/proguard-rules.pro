# XX-Note release rules.
#
# Hardening #10: deliberately empty beyond this note. Every library with an
# R8-sensitive surface ships consumer rules that AGP applies automatically —
# verified against the resolved artifacts in the Gradle cache, not assumed:
#
# - Room 2.7.2 (room-runtime-android AAR, bundled proguard.txt): keeps
#   constructors of `RoomDatabase` subclasses — covers the KSP-generated
#   XxDatabase_Impl lookup that Room's reflection path depends on. The DAO
#   *_Impl classes are instantiated directly from generated code, so plain
#   reachability keeps them.
# - WorkManager 2.10.0 (work-runtime AAR, bundled proguard.txt):
#   `-keepnames class * extends androidx.work.ListenableWorker` plus public
#   constructors and WorkerParameters — covers SyncWorker : CoroutineWorker,
#   which WorkerFactory instantiates by reflective class-name lookup.
# - OkHttp 4.12.0 (META-INF/proguard/okhttp3.pro in the jar): keeps the
#   resource-loaded PublicSuffixDatabase; dontwarns for optional platform
#   security providers (Conscrypt/BouncyCastle/OpenJSSE).
# - Compose / lifecycle / navigation: ship their own consumer rules; none of
#   this app's usage touches Java reflection.
#
# Gson never shipped: the WS5 plan said "+ Gson backup" but no Gson dependency
# or usage exists anywhere in main source (only design.md D16 mentions the
# dropped idea), so no Gson rules belong here. Same audit result for
# kotlinx.serialization, Moshi, org.json: unused.
#
# The app itself adds no keep-worthy surface: no @Keep, no Parcelable, no
# Serializable, no Class.forName. The one factory call,
# XmlPullParserFactory.newInstance() in PropfindParser, resolves to the
# platform's built-in parser — platform classes are outside R8's reach.
#
# None of this is trusted untested: MinifiedSmokeTest (app/src/androidTest,
# hardening #14b/#10) exercises a minified APK's Room open + FTS query, the
# OkHttp client build under the app's network security config, and a real
# WorkManager enqueue-to-SUCCEEDED. It requires attached hardware to execute;
# no run has happened yet.
