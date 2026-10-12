// Leaf Lite — root build script.
// The only plugins in this project are the Android application plugin and the
// Kotlin Android plugin; everything else is a plain dependency (see app/build.gradle.kts).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
