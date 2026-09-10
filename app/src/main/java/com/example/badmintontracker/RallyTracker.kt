package com.example.badmintontracker

/**
 * Groups detected shots into rallies using two signals:
 *  1. A serve flag from ServeDetector — a genuine serve (near-stationary
 *     start) always forces a new rally, regardless of the time gap.
 *  2. A gap heuristic fallback: if more than [rallyGapMillis] passes
 *     between one shot and the next with no serve detected, the previous
 *     rally is still considered over and a new one starts.
 *
 * Serve detection (README.md section 10) directly targets the gap
 * heuristic's original known limitation: a slow, high-arc rally with a
 * genuinely long pause between two clears no longer gets miscounted as two
 * rallies, because neither clear looks like a serve — the gap alone isn't
 * what decides it anymore. The gap fallback stays in place for sessions
 * where the serve signature misfires or isn't confident enough (see
 * ServeDetector's known limitations) — a big enough gap still ends a
 * rally even without an explicit serve flag.
 *
 * Still imperfect in the same spirit as before:
 *  - Warm-up swings or shadow practice close together in time (no serve
 *    pause between them) can still look like one rally even if no shuttle
 *    was involved.
 *  - A long pause that isn't actually a serve (picking up a dropped
 *    shuttle, a break between points) can still start a "new" rally early.
 * Tune [rallyGapMillis] against your own play if 3s feels wrong, same as
 * before — it's now a fallback rather than the only signal.
 */
class RallyTracker(
    private val rallyGapMillis: Long = 3000L
) {
    private var lastShotTime = 0L
    private var shotsInCurrentRally = 0

    var rallyCount = 0
        private set
    var longestRallyShots = 0
        private set
    var currentRallyShots = 0
        private set

    /**
     * Feed this with the timestamp of every detected shot (any shot type).
     * Pass [isServe] = true (from ServeDetector.isServe) when this shot's
     * pre-swing stillness signature says it's a serve — that always starts
     * a new rally, independent of [rallyGapMillis].
     */
    fun onShot(timestampMillis: Long, isServe: Boolean = false) {
        val isNewRally = lastShotTime == 0L || isServe ||
            (timestampMillis - lastShotTime) > rallyGapMillis
        shotsInCurrentRally = if (isNewRally) {
            rallyCount++
            1
        } else {
            shotsInCurrentRally + 1
        }
        if (shotsInCurrentRally > longestRallyShots) longestRallyShots = shotsInCurrentRally
        currentRallyShots = shotsInCurrentRally
        lastShotTime = timestampMillis
    }

    fun reset() {
        lastShotTime = 0L
        shotsInCurrentRally = 0
        rallyCount = 0
        longestRallyShots = 0
        currentRallyShots = 0
    }
}
