package com.example.badmintontracker

/**
 * v1 shot-type classifier — plain thresholds on the features SmashDetector
 * already extracts (peak acceleration, peak gyro/rotation, swing duration,
 * vertical-vs-horizontal ratio). This is NOT a trained ML model. It's a
 * reasonable starting point based on how these shots actually differ:
 *
 *  - Smash:  fast wrist snap (high gyro), short duration, downward/overhead
 *  - Clear:  high power but a longer, more overhead-arc swing
 *  - Drop:   soft, controlled — low acceleration AND low rotation
 *
 * Expect to retune the thresholds below against your own labeled swings —
 * see "Classifier calibration" in README.md.
 */
enum class ShotType { SMASH, CLEAR, DROP, UNKNOWN }

class ShotClassifier(
    private val smashGyroMin: Float = 6f,        // rad/s — fast wrist snap
    private val smashDurationMaxMs: Long = 130L, // smashes are quick
    private val dropAccelMax: Float = 32f,       // soft, controlled touch
    private val dropGyroMax: Float = 3f,
    private val clearVerticalMin: Float = 0.45f  // more overhead/upward trajectory
) {
    fun classify(shot: SmashDetector.ShotEvent): ShotType = when {
        shot.peakGyroMagnitude >= smashGyroMin &&
            shot.durationMillis <= smashDurationMaxMs &&
            shot.verticalRatio >= clearVerticalMin -> ShotType.SMASH

        shot.peakAccelMagnitude <= dropAccelMax &&
            shot.peakGyroMagnitude <= dropGyroMax -> ShotType.DROP

        shot.verticalRatio >= clearVerticalMin -> ShotType.CLEAR

        else -> ShotType.UNKNOWN
    }
}
