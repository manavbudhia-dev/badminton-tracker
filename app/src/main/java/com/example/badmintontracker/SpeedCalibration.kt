package com.example.badmintontracker

import android.content.Context
import kotlin.math.abs

/**
 * A calibration line converting SmashDetector's raw peak wrist-acceleration
 * into an actual km/h figure:
 *   estimatedSpeedKph = intercept + slope * (peakAccelMagnitude - triggerThreshold)
 * See SmashDetector.kt for the "why" — the watch has no way to measure true
 * shuttlecock speed, so this line is the only thing standing between a raw
 * sensor number and a believable speed reading.
 *
 * [calibratedAt] is null for the built-in factory line (SmashDetector's own
 * constructor defaults) — that line was picked to produce plausible-looking
 * numbers out of the box, not measured against anything real. UI should
 * treat a null [calibratedAt] as "uncalibrated" and say so, rather than
 * silently showing a confident number the user has no reason to trust yet.
 */
data class SpeedCalibration(
    val slope: Float,
    val intercept: Float,
    val calibratedAt: Long? = null
) {
    val isCalibrated: Boolean get() = calibratedAt != null

    companion object {
        // Mirrors SmashDetector's own constructor defaults exactly, so
        // "uncalibrated" always means "identical to what a fresh
        // SmashDetector() would have done anyway" — never a second,
        // separately-drifting set of made-up numbers.
        val FACTORY_DEFAULT = SpeedCalibration(slope = 4.2f, intercept = 60f, calibratedAt = null)
    }
}

/** Persists the user's calibration line so it survives app restarts. */
object SpeedCalibrationStore {
    private const val PREFS = "badminton_calibration"
    private const val KEY_SLOPE = "slope"
    private const val KEY_INTERCEPT = "intercept"
    private const val KEY_CALIBRATED_AT = "calibrated_at"

    fun load(context: Context): SpeedCalibration {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CALIBRATED_AT)) return SpeedCalibration.FACTORY_DEFAULT
        return SpeedCalibration(
            slope = prefs.getFloat(KEY_SLOPE, SpeedCalibration.FACTORY_DEFAULT.slope),
            intercept = prefs.getFloat(KEY_INTERCEPT, SpeedCalibration.FACTORY_DEFAULT.intercept),
            calibratedAt = prefs.getLong(KEY_CALIBRATED_AT, 0L).takeIf { it != 0L }
        )
    }

    fun save(context: Context, calibration: SpeedCalibration) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SLOPE, calibration.slope)
            .putFloat(KEY_INTERCEPT, calibration.intercept)
            .putLong(KEY_CALIBRATED_AT, calibration.calibratedAt ?: 0L)
            .apply()
    }

    /** Back to the factory line — e.g. if a calibration attempt looked wrong. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** Steps in the on-watch calibration wizard — see CalibrationScreen in MainActivity.kt. */
enum class CalibrationStep {
    INTRO,
    CAPTURING_SOFT,
    ENTER_SOFT_SPEED,
    CAPTURING_HARD,
    ENTER_HARD_SPEED,
    ERROR_TOO_SIMILAR,
    DONE
}

/**
 * Walks through a single two-point calibration attempt: capture a "soft"
 * reference swing and a "hard" reference swing, pair each with a real speed
 * the user measured some other way (radar gun, a speed-gun app, a known
 * reference smash), then solve the two points for a straight line.
 *
 * Two points is the minimum needed for a line and keeps the on-watch flow
 * to two swings rather than a longer guided session — more reference points
 * spread across more swings would fit a sturdier line if the two swings
 * turn out noisy, but that's a natural follow-up rather than what this
 * first version does.
 */
class CalibrationSession(
    private val triggerThreshold: Float
) {
    private var softAccel: Float? = null
    private var softSpeedKph: Float? = null
    private var hardAccel: Float? = null
    private var hardSpeedKph: Float? = null

    val hasSoftSwing: Boolean get() = softAccel != null
    val hasHardSwing: Boolean get() = hardAccel != null

    fun recordSoftSwing(peakAccelMagnitude: Float) { softAccel = peakAccelMagnitude }
    fun recordSoftSpeed(speedKph: Float) { softSpeedKph = speedKph }
    fun recordHardSwing(peakAccelMagnitude: Float) { hardAccel = peakAccelMagnitude }
    fun recordHardSpeed(speedKph: Float) { hardSpeedKph = speedKph }

    /**
     * Solves the two reference points for a slope/intercept line, or
     * returns null if the two swings were too close in raw power to tell
     * apart (would produce a near-vertical or divide-by-zero line) — the
     * caller should ask for a clearer soft/hard contrast rather than save a
     * degenerate result silently.
     */
    fun solve(): SpeedCalibration? {
        val a1 = softAccel ?: return null
        val s1 = softSpeedKph ?: return null
        val a2 = hardAccel ?: return null
        val s2 = hardSpeedKph ?: return null

        if (abs(a2 - a1) < MIN_ACCEL_SEPARATION) return null

        val slope = (s2 - s1) / (a2 - a1)
        val intercept = s1 - slope * (a1 - triggerThreshold)
        return SpeedCalibration(slope = slope, intercept = intercept, calibratedAt = System.currentTimeMillis())
    }

    companion object {
        private const val MIN_ACCEL_SEPARATION = 3f
    }
}
