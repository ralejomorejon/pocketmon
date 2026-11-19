plugins {
    id("com.android.application") version "9.5.1" apply false
    // Not applied (AGP 9 has built-in Kotlin); declared to pin the Kotlin Gradle Plugin version.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
