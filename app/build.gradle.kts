import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Hardening #5: release signing secrets come from local.properties (gitignored)
// first, environment second — never source control, never hardcoded here. The
// four keys, and their env-var fallbacks:
//
//   xx.release.keystore.path     XX_RELEASE_STORE_FILE      (resolved against the repo root; absolute paths win)
//   xx.release.keystore.password XX_RELEASE_STORE_PASSWORD
//   xx.release.key.alias         XX_RELEASE_KEY_ALIAS
//   xx.release.key.password      XX_RELEASE_KEY_PASSWORD
//
// Contract: only when ALL FOUR resolve does a signingConfig attach. Anything
// less — CI, this dev machine — leaves release unsigned exactly as today
// (app-release-unsigned.apk); a partial set is ignored rather than half-wired.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun xxReleaseSecret(propertyKey: String, envVar: String): String? =
    localProperties.getProperty(propertyKey)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(envVar)?.trim()?.takeIf { it.isNotEmpty() }

private val xxReleaseSecrets: List<String>? = listOf(
    xxReleaseSecret("xx.release.keystore.path", "XX_RELEASE_STORE_FILE"),
    xxReleaseSecret("xx.release.keystore.password", "XX_RELEASE_STORE_PASSWORD"),
    xxReleaseSecret("xx.release.key.alias", "XX_RELEASE_KEY_ALIAS"),
    xxReleaseSecret("xx.release.key.password", "XX_RELEASE_KEY_PASSWORD"),
).takeIf { values -> null !in values }?.map { it as String }

android {
    namespace = "com.piercingxx.xxnote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.xxnote"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Instrumented tests (hardening #14) — execution needs attached hardware.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        xxReleaseSecrets?.let { secrets ->
            val (keystorePath, keystorePass, aliasName, keyPass) = secrets
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystorePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // findByName is null when no secrets resolved above, and a null
            // signingConfig means unsigned — the pre-#5 behaviour, preserved.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources for Room/Context tests.
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose stack (D11).
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Room — cache and outbox, never the source of truth (D1, design §11).
    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // WorkManager — background sync only, never a foreground service (§4.4).
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // OkHttp — the one host's transport (R8). No other network library is
    // allowed anywhere in the tree; the CI dependency audit checks this.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Pure policy core — the part that must be correct (design §5).
    implementation(project(":core"))

    // Unit tests: JVM for pure classes, Robolectric for Room/Context,
    // MockWebServer for the one-host guard and WebDAV client.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    // Instrumented tests (hardening #14): KeystoreKeyOps on real hardware and
    // the minified-build smoke paths have no JVM stand-in, so these compile
    // here but only run under connectedDebugAndroidTest against a device.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(kotlin("test"))
}
