plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // For Room's annotation processor (SessionDatabase/SessionDao in the
    // phone module) — kapt rather than ksp so its version doesn't need to
    // be matched against a separate ksp release train from the Kotlin
    // Gradle plugin version above.
    id("org.jetbrains.kotlin.kapt") version "2.1.20" apply false
}
