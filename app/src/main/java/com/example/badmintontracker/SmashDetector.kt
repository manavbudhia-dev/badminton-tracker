package com.example.badmintontracker

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects "shot events" from a stream of linear-acceleration + gyroscope
 * samples, and estimates a calibrated swing speed. Also captures the extra
 * features (peak rotation, swing duration, vertical component) that
 * ShotClassifier uses to guess the shot type (smash / clear / drop).
 *
 * IMPORTANT — what this measures:
 * The watch is on your wrist, not the racket or the shuttle, so there is no
 * way to measure true shuttlecock speed here. This measures peak wrist
 * acceleration + rotation during a swing and converts that into an
 * approximate km/h using a calibration line you tune yourself — see
 * README.md. Until calibrated, treat the numbers as a relative power
 * score, not a real speed.
 *
 * NOTE: this file replaces the earlier single-sensor version. The API is
 * now split into onAccelSample() / onGyroSample() since we read two
 * sensors now — update MainActivity.kt to the new version too.
 *
 * Serve signature: a serve starts from a near-stationary wrist (you pause,
 * set up, then flick/push) whereas a shot in the middle of a rally flows
 * directly out of the recovery from the previous stroke — the wrist is
 * already moving. So alongside the swing features above, this also tracks
 * how long the wrist was "quiet" (below [stillnessAccelThreshold])
 * immediately before each trigger, and reports it on the ShotEvent as
 * [ShotEvent.precededByStillnessMillis]. That's the whole signature — no
 * new sensor, just remembering the last time you moved. See ServeDetector.kt
 * for how it turns this into an isServe flag, and README.md section 9.
 */
class SmashDetector(
    private val triggerThreshold: Float = 25f,
    private val debounceMillis: Long = 400L,
    private val windowMillis: Long = 150L,
    private val calibrationSlope: Float = 4.2f,
    private val calibrationIntercept: Float = 60f,
    private val stillnessAccelThreshold: Float = 3f, // below this = "not moving", for serve detection
    // Simple moving-average window (in samples) applied to the raw accel
    // magnitude before it's compared against any threshold or tracked as a
    // peak. Sweat/a loose strap tends to show up as brief single-sample
    // spikes rather than a sustained rise, so averaging a couple of
    // consecutive samples filters a lot of that out while barely affecting
    // real swings (a swing's rise lasts 100ms+, this window is a few
    // SENSOR_DELAY_GAME samples, i.e. well under 50ms). Set to 1 to disable
    // smoothing entirely and go back to raw per-sample values.
    private val accelSmoothingSamples: Int = 3
) {
    private var lastTriggerTime = 0L
    private var triggerStartTime = 0L
    private var windowEndTime = 0L
    private var windowPeakAccel = 0f
    private var windowPeakGyro = 0f
    private var windowPeakVerticalZ = 0f
    private var windowPrecededByStillnessMillis = 0L
    private var awaitingWindowClose = false
    private var latestGyroMagnitude = 0f

    // Last time accel magnitude exceeded stillnessAccelThreshold outside a
    // swing window — i.e. the last moment the wrist was doing *anything*.
    // 0L means "no movement seen yet this session" (treated as indefinitely
    // quiet — see onAccelSample below).
    private var lastMovementTime = 0L

    // Ring buffer backing the moving average described above.
    private val smoothingBuffer = FloatArray(accelSmoothingSamples.coerceAtLeast(1))
    private var smoothingIndex = 0
    private var smoothingFilled = 0
    private var smoothingSum = 0f

    private fun smooth(rawMagnitude: Float): Float {
        if (accelSmoothingSamples <= 1) return rawMagnitude
        smoothingSum -= smoothingBuffer[smoothingIndex]
        smoothingBuffer[smoothingIndex] = rawMagnitude
        smoothingSum += rawMagnitude
        smoothingIndex = (smoothingIndex + 1) % smoothingBuffer.size
        if (smoothingFilled < smoothingBuffer.size) smoothingFilled++
        return smoothingSum / smoothingFilled
    }

    data class ShotEvent(
        val timestampMillis: Long,
        val peakAccelMagnitude: Float,
        val peakGyroMagnitude: Float,
        val durationMillis: Long,
        val verticalRatio: Float,       // 0..1, rough proxy for an overhead/downward swing
        val estimatedSpeedKph: Float,
        val precededByStillnessMillis: Long // how long the wrist was quiet right before this swing started
    )

    /** Feed this from the gyroscope listener on every sample. */
    fun onGyroSample(x: Float, y: Float, z: Float) {
        latestGyroMagnitude = sqrt(x * x + y * y + z * z)
    }

    /** Feed this from the linear-acceleration listener on every sample. */
    fun onAccelSample(timestampMillis: Long, x: Float, y: Float, z: Float): ShotEvent? {
        val magnitude = smooth(sqrt(x * x + y * y + z * z))

        if (awaitingWindowClose) {
            if (magnitude > windowPeakAccel) windowPeakAccel = magnitude
            if (latestGyroMagnitude > windowPeakGyro) windowPeakGyro = latestGyroMagnitude
            if (abs(z) > windowPeakVerticalZ) windowPeakVerticalZ = abs(z)
            if (magnitude > stillnessAccelThreshold) lastMovementTime = timestampMillis

            if (timestampMillis >= windowEndTime) {
                awaitingWindowClose = false
                val speed = calibrationIntercept +
                    calibrationSlope * (windowPeakAccel - triggerThreshold)
                val verticalRatio = if (windowPeakAccel > 0f) {
                    (windowPeakVerticalZ / windowPeakAccel).coerceIn(0f, 1f)
                } else 0f
                return ShotEvent(
                    timestampMillis = timestampMillis,
                    peakAccelMagnitude = windowPeakAccel,
                    peakGyroMagnitude = windowPeakGyro,
                    durationMillis = timestampMillis - triggerStartTime,
                    verticalRatio = verticalRatio,
                    estimatedSpeedKph = speed.coerceAtLeast(0f),
                    precededByStillnessMillis = windowPrecededByStillnessMillis
                )
            }
            return null
        }

        val cooledDown = timestampMillis - lastTriggerTime > debounceMillis
        if (cooledDown && magnitude > triggerThreshold) {
            // How long was the wrist quiet right up until this swing started?
            // No movement recorded yet this session counts as "indefinitely
            // quiet" (Long.MAX_VALUE) — the very first shot of a session has
            // nothing but stillness behind it.
            windowPrecededByStillnessMillis = if (lastMovementTime == 0L) {
                Long.MAX_VALUE
            } else {
                timestampMillis - lastMovementTime
            }

            lastTriggerTime = timestampMillis
            triggerStartTime = timestampMillis
            windowPeakAccel = magnitude
            windowPeakGyro = latestGyroMagnitude
            windowPeakVerticalZ = abs(z)
            windowEndTime = timestampMillis + windowMillis
            awaitingWindowClose = true
            lastMovementTime = timestampMillis // this sample is itself movement
        } else if (magnitude > stillnessAccelThreshold) {
            lastMovementTime = timestampMillis
        }
        return null
    }
}
