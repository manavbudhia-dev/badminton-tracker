plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // KSP version is pinned to the exact Kotlin version above (the
    // "<kotlin-version>-<ksp-version>" suffix) — needed for Room's
    // annotation processor now that SessionHistoryStore is Room-backed.
    id("com.google.devtools.ksp") version "2.1.20-1.0.31" apply false
}
