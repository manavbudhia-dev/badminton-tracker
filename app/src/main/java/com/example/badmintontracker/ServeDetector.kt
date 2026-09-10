package com.example.badmintontracker

/**
 * Flags a shot as serve-like — see README.md section 10 for the "why".
 *
 * Deliberately a separate boolean, not a 4th ShotType next to
 * SMASH/CLEAR/DROP/UNKNOWN:
 *  - A serve's defining signature is *when* it happens (after a pause),
 *    not its power/shape — a serve can be a soft short push or a hard
 *    flick, both of which already look like DROP or SMASH to
 *    ShotClassifier/MLShotClassifier. Folding it into ShotType would mean
 *    a serve competing with those categories instead of sitting alongside
 *    them.
 *  - It keeps working unchanged with the ML classifier without retraining
 *    shot_classifier.tflite — MLShotClassifier's 4-class model has no idea
 *    about pre-swing stillness, and doesn't need to.
 *
 * v1 is a single threshold on SmashDetector.ShotEvent.precededByStillnessMillis
 * — same "reasonable starting point, tune against your own play" spirit as
 * ShotClassifier's thresholds. See "Known limitations" in README.md section 9
 * for what this heuristic can't tell apart.
 */
class ServeDetector(
    private val minQuietMillisBeforeSwing: Long = 500L
) {
    fun isServe(shot: SmashDetector.ShotEvent): Boolean =
        shot.precededByStillnessMillis >= minQuietMillisBeforeSwing
}
