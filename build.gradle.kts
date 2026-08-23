// Top-level build file. Pins: AGP 8.9.1 + Kotlin 2.1.20 on compileSdk 35
// (design targets SDK 37; 37 is unavailable in this toolchain — same ruling as xx-phone).
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // KSP release train matched to Kotlin 2.1.20.
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
