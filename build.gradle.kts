plugins {
    // 8.5.2 -> 8.7.2: Wear Compose 1.6.x (and the Compose 1.9.x it pulls in
    // transitively) declare a minimum AGP of 8.6.0 in their AAR metadata.
    // 8.7.2 is the newest AGP that Kotlin 2.1.20 officially supports, and it
    // needs Gradle >= 8.9 (the CI workflow pins 8.10.2).
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // KSP version is pinned to the exact Kotlin version above (the
    // "<kotlin-version>-<ksp-version>" suffix) — needed for Room's
    // annotation processor now that SessionHistoryStore is Room-backed.
    id("com.google.devtools.ksp") version "2.1.20-1.0.31" apply false
}
