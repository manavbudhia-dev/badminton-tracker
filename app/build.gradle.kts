plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // Room's annotation processor (see SessionDatabase.kt)
}

android {
    namespace = "com.example.badmintontracker"
    // Bumped from 34 -> 35 alongside the Wear Compose / Activity Compose
    // version bumps below: LocalAmbientModeManager (Wear Compose 1.6.x) and
    // LocalActivity (Activity Compose 1.10.x+) are recent enough that their
    // AARs declare a minCompileSdk of 35, which fails the build under a
    // lower compileSdk rather than just warning.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.badmintontracker"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 1.9.0 -> 1.10.1: LocalActivity (used by rememberAmbientModeManager,
    // see MainActivity's ambient-mode setup) was added in 1.10.0-alpha03.
    implementation("androidx.activity:activity-compose:1.10.1")
    // 1.6.8 -> 1.7.6: LocalActivity needs compositionLocalWithComputedDefaultOf,
    // only available from Compose runtime 1.7.0 (activity-compose 1.10.x
    // pulls that in transitively, but the app's own compose-ui needs to be
    // compatible too).
    implementation("androidx.compose.ui:ui:1.7.6")
    // 1.4.0 -> 1.6.2: LocalAmbientModeManager / rememberAmbientModeManager
    // (see MainActivity's ambient-mode setup) landed in Wear Compose 1.6.0.
    implementation("androidx.wear.compose:compose-material:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // Ongoing Activity API — the persistent tappable icon at the bottom of
    // the watch face while a session is running (see ExerciseSessionService's
    // buildNotification()/publishOngoingActivity()).
    implementation("androidx.wear:wear-ongoing:1.1.0")

    // Room — replaces SessionHistoryStore's old SharedPreferences+file
    // storage (see SessionDatabase.kt).
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Wear OS Tile ("Start session" from the watch face — see StartSessionTileService.kt).
    // Pinned to 1.5.0/1.3.0: tiles 1.6.x raised the minimum to compileSdk 35 +
    // AGP 8.6.0 (for the new Material3TileService); this project is now on
    // compileSdk 35 but still AGP 8.5.2, so 1.5.0 (the last release before
    // that bump) stays the safe pin — bump alongside a future AGP upgrade.
    implementation("androidx.wear.tiles:tiles:1.5.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.3.0")
}

ksp {
    // Schema history checked into the repo — required for Room to generate
    // and validate migrations as the tracking metrics evolve (see
    // SessionDatabase.kt's class doc for how to add one).
    arg("room.schemaLocation", "$projectDir/schemas")
}
