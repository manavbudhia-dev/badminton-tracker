package com.example.badmintontracker

/**
 * Heart Rate Recovery (HRR) micro-metrics.
 *
 * Badminton only gives you 10-20 seconds between a rally ending and the
 * next serve. How high your heart rate spikes during a long rally matters
 * less than how much of that spike you've shed by the time you're serving
 * again — that's the actual conditioning signal. This tracks, per rally:
 * the peak heart rate reached, and how far it had dropped by the time the
 * next rally's first shot (the serve) arrives.
 *
 * Not the same thing as the clinical HRR test (heart rate drop in the full
 * 60 seconds immediately after exertion stops completely) — a badminton
 * rest window is much shorter and interrupted by the next point starting.
 * Treat these numbers as a within-match, relative fitness indicator to
 * trend over time, not a clinical measurement.
 *
 * Feed it from two independent live streams:
 *  - every heart-rate sample, via [onHeartRateSample] (see
 *    ExerciseSessionService.onHeartRateSample)
 *  - every rally boundary, via [onRallyBoundary] (see MainActivity's
 *    onSensorChanged, where RallyTracker.onShot reports isNewRally)
 * Samples are buffered and only sliced into "this rally" vs "this rest"
 * retroactively once a boundary arrives — in real time there's no way to
 * know a rally has ended until either the next serve or the gap timeout
 * fires, so live routing based on a running flag doesn't work).
 */

data class RallyRecoveryPoint(
    val restStartMillis: Long, // end of the rally that just finished
    val restEndMillis: Long,   // start of the next rally (the serve that ends the rest)
    val peakBpm: Double,       // highest heart rate observed during the rally that just finished
    val bpmAtRestEnd: Double,  // heart rate right as the next serve happens
    val bpmAt15s: Double?      // heart rate ~15s into the rest, if the rest lasted at least that long
) {
    val restDurationMillis: Long get() = restEndMillis - restStartMillis
    val recoveryBpm: Double get() = peakBpm - bpmAtRestEnd

    /** Recovery normalized per second of rest — comparable across rallies with different rest lengths. */
    val recoveryPerSecond: Double
        get() = if (restDurationMillis <= 0) 0.0 else recoveryBpm / (restDurationMillis / 1000.0)
}

data class HrrSummary(
    val rallyCount: Int,
    val avgPeakBpm: Double,
    val avgRecoveryBpm: Double,
    val avgRecoveryPerSecond: Double,
    val bestRecoveryBpm: Double,
    val worstRecoveryBpm: Double
)

/** Null if there are no completed rest windows yet to summarize. */
fun summarizeRecovery(points: List<RallyRecoveryPoint>): HrrSummary? {
    if (points.isEmpty()) return null
    return HrrSummary(
        rallyCount = points.size,
        avgPeakBpm = points.map { it.peakBpm }.average(),
        avgRecoveryBpm = points.map { it.recoveryBpm }.average(),
        avgRecoveryPerSecond = points.map { it.recoveryPerSecond }.average(),
        bestRecoveryBpm = points.maxOf { it.recoveryBpm },
        worstRecoveryBpm = points.minOf { it.recoveryBpm }
    )
}

class HeartRateRecoveryTracker {
    // Rolling buffer of every (timestamp, bpm) sample seen — sliced by time
    // range once a rally boundary tells us where "the rally" and "the rest"
    // actually were. Trimmed so it never grows past a couple of rally+rest
    // cycles' worth, since nothing older than that is ever sliced from it.
    private val samples = ArrayDeque<Pair<Long, Double>>()

    // The start of the rally currently in progress — i.e. the end of the
    // *previous* rest window. Null until the first rally boundary arrives,
    // which is why the very first rally of a session never gets a recovery
    // point: there's no earlier boundary to say exactly when it started.
    private var currentRallyStartMillis: Long? = null

    private val _points = mutableListOf<RallyRecoveryPoint>()
    val points: List<RallyRecoveryPoint> get() = _points

    /** Call on every heart-rate sample, whether it lands mid-rally or mid-rest — this doesn't need to know which. */
    fun onHeartRateSample(timestampMillis: Long, bpm: Double) {
        samples.addLast(timestampMillis to bpm)
        while (samples.isNotEmpty() && timestampMillis - samples.first().first > MAX_BUFFER_MILLIS) {
            samples.removeFirst()
        }
    }

    /**
     * Call the moment a new rally starts (RallyTracker.onShot returned
     * true). [restStart] is the timestamp of the last shot in the rally
     * that just ended; [restEnd] is this new rally's first shot (the serve)
     * — together they're exactly the rest window that just elapsed.
     */
    fun onRallyBoundary(restStart: Long, restEnd: Long) {
        val rallyStart = currentRallyStartMillis
        if (rallyStart != null) {
            val peak = samples.filter { it.first in rallyStart..restStart }.maxOfOrNull { it.second }
            val restSamples = samples.filter { it.first in restStart..restEnd }
            if (peak != null && restSamples.isNotEmpty()) {
                _points.add(
                    RallyRecoveryPoint(
                        restStartMillis = restStart,
                        restEndMillis = restEnd,
                        peakBpm = peak,
                        bpmAtRestEnd = restSamples.last().second,
                        bpmAt15s = restSamples.firstOrNull { it.first - restStart >= 15_000L }?.second
                    )
                )
            }
        }
        currentRallyStartMillis = restEnd
    }

    fun reset() {
        samples.clear()
        currentRallyStartMillis = null
        _points.clear()
    }

    companion object {
        // Comfortably more than one rally+rest cycle ever needs, so the
        // buffer never grows unbounded across a long session.
        private const val MAX_BUFFER_MILLIS = 5 * 60 * 1000L
    }
}
