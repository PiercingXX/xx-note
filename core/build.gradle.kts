// Pure JVM policy core (design §14). Zero external dependencies on purpose:
// the part that must be correct must be provable with no device attached.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
