plugins {
    id("com.android.application")
}

android {
    namespace = "com.piercingxx.xxnote.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piercingxx.xxnote.probe"
        minSdk = 31
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
}
