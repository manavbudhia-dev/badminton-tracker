package com.example.badmintontracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Muscle asymmetry / conditioning tracking — badminton is a unilateral
 * sport, so this exists to answer "is my racket arm and shoulder actually
 * getting stronger relative to the other side from conditioning work?"
 *
 * IMPORTANT — why this isn't built on the Watch's BIA sensor:
 * Watch 7's Body Composition (BIA) sensor is a whole-body measurement — the
 * circuit runs hand-to-hand through your torso, so it reports one skeletal
 * muscle mass number for your entire body, not a per-limb breakdown. There
 * is no way for a single wrist-worn sensor to isolate "left arm" from
 * "right arm" the way a multi-electrode clinical BIA device can. On top of
 * that, the BIA scan itself is a Samsung Health app feature — it isn't
 * exposed through Health Services or Health Connect, so a third-party app
 * like this one can't trigger a scan or read impedance data even for the
 * whole body.
 *
 * What this module tracks instead, with sensors this app actually has
 * access to:
 *  - [ConditioningSet]: reps and a relative swing-power score for a single
 *    arm during a conditioning set (e.g. wrist curls), captured from the
 *    accelerometer via [RepCounter] — comparable session-to-session and
 *    side-to-side, but NOT a real force/weight unit (there's no reference
 *    point to calibrate it against, unlike SmashDetector's speed line).
 *  - [BodyCompositionEntry]: a manually-entered whole-body skeletal muscle
 *    mass reading, logged here whenever you run a BIA scan in Samsung
 *    Health, so you get a trend of it over time in one place alongside
 *    your arm balance numbers.
 */

enum class Arm { DOMINANT, NON_DOMINANT }

data class ConditioningSet(
    val timestamp: Long,
    val arm: Arm,
    val weightKg: Float,
    val reps: Int,
    val avgPeakAccel: Float // relative power score, not a real force unit — see module doc above
)

data class BodyCompositionEntry(
    val timestamp: Long,
    val skeletalMuscleMassKg: Float
)

data class ArmBalanceSummary(
    val dominantSets: Int,
    val dominantReps: Int,
    val dominantAvgPower: Float,
    val nonDominantSets: Int,
    val nonDominantReps: Int,
    val nonDominantAvgPower: Float
) {
    /**
     * How many more total reps the dominant arm has logged than the
     * non-dominant one, as a percentage of the non-dominant total. Null
     * until there's at least one non-dominant-arm set to compare against —
     * a percentage against zero would be meaningless, not just large.
     */
    val repImbalancePercent: Float?
        get() = if (nonDominantReps == 0) null
        else ((dominantReps - nonDominantReps).toFloat() / nonDominantReps) * 100f
}

/** Filters to sets/entries within [sinceMillis] and compares the two arms. */
fun computeArmBalance(sets: List<ConditioningSet>, sinceMillis: Long): ArmBalanceSummary {
    val recent = sets.filter { it.timestamp >= sinceMillis }
    val dominant = recent.filter { it.arm == Arm.DOMINANT }
    val nonDominant = recent.filter { it.arm == Arm.NON_DOMINANT }
    return ArmBalanceSummary(
        dominantSets = dominant.size,
        dominantReps = dominant.sumOf { it.reps },
        dominantAvgPower = if (dominant.isEmpty()) 0f else dominant.map { it.avgPeakAccel }.average().toFloat(),
        nonDominantSets = nonDominant.size,
        nonDominantReps = nonDominant.sumOf { it.reps },
        nonDominantAvgPower = if (nonDominant.isEmpty()) 0f else nonDominant.map { it.avgPeakAccel }.average().toFloat()
    )
}

/** Steps in the on-watch "Arm Balance" flow — see StrengthScreen in MainActivity.kt. */
enum class StrengthMode {
    MENU,
    SET_SETUP,
    LOGGING_SET,
    SET_SUMMARY,
    LOG_BODY_COMP,
    VIEW_BALANCE
}

/**
 * Counts reps of a slow, controlled, repetitive lift (wrist curls, forearm
 * conditioning) from accelerometer samples — the same peak-over-threshold
 * idea as SmashDetector, but tuned for a deliberate lift instead of an
 * explosive racket swing: a much lower trigger threshold (a curl is far
 * gentler than a smash) and a longer debounce (a rep takes roughly a
 * second, not the ~400ms between racket swings).
 *
 * These defaults are a first estimate, not measured against real reps —
 * same honest caveat as SmashDetector's original factory calibration. If
 * reps are under- or over-counting for your motion, adjust the constructor
 * defaults below and rebuild; a proper in-app tuning flow for this (like
 * SpeedCalibration.kt gives the smash detector) is a natural follow-up.
 */
class RepCounter(
    private val triggerThreshold: Float = 8f,
    private val debounceMillis: Long = 700L
) {
    private var lastTriggerTime = 0L
    private val peaks = mutableListOf<Float>()

    fun reset() {
        peaks.clear()
        lastTriggerTime = 0L
    }

    /** Feed every accelerometer sample. Returns true exactly when a new rep is counted. */
    fun onAccelSample(timestampMillis: Long, x: Float, y: Float, z: Float): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        val cooledDown = timestampMillis - lastTriggerTime > debounceMillis
        if (cooledDown && magnitude > triggerThreshold) {
            lastTriggerTime = timestampMillis
            peaks.add(magnitude)
            return true
        }
        return false
    }

    val repCount: Int get() = peaks.size
    val averagePeak: Float get() = if (peaks.isEmpty()) 0f else peaks.average().toFloat()
}

/** Persists conditioning sets and body-composition log entries. */
object StrengthTrackingStore {
    private const val PREFS = "badminton_strength"
    private const val KEY_SETS = "conditioning_sets"
    private const val KEY_BODY_COMP = "body_composition"
    private const val MAX_ENTRIES = 200

    fun loadSets(context: Context): List<ConditioningSet> {
        val array = loadRawArray(context, KEY_SETS)
        return (0 until array.length()).map { jsonToSet(array.getJSONObject(it)) }.sortedByDescending { it.timestamp }
    }

    fun saveSet(context: Context, set: ConditioningSet) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRawArray(context, KEY_SETS)
        existing.put(setToJson(set))
        while (existing.length() > MAX_ENTRIES) existing.remove(0)
        prefs.edit().putString(KEY_SETS, existing.toString()).apply()
    }

    fun loadBodyComposition(context: Context): List<BodyCompositionEntry> {
        val array = loadRawArray(context, KEY_BODY_COMP)
        return (0 until array.length()).map { jsonToBodyComp(array.getJSONObject(it)) }.sortedByDescending { it.timestamp }
    }

    fun saveBodyComposition(context: Context, entry: BodyCompositionEntry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRawArray(context, KEY_BODY_COMP)
        existing.put(bodyCompToJson(entry))
        while (existing.length() > MAX_ENTRIES) existing.remove(0)
        prefs.edit().putString(KEY_BODY_COMP, existing.toString()).apply()
    }

    private fun loadRawArray(context: Context, key: String): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun setToJson(set: ConditioningSet) = JSONObject().apply {
        put("timestamp", set.timestamp)
        put("arm", set.arm.name)
        put("weightKg", set.weightKg.toDouble())
        put("reps", set.reps)
        put("avgPeakAccel", set.avgPeakAccel.toDouble())
    }

    private fun jsonToSet(json: JSONObject) = ConditioningSet(
        timestamp = json.optLong("timestamp"),
        arm = runCatching { Arm.valueOf(json.optString("arm")) }.getOrDefault(Arm.DOMINANT),
        weightKg = json.optDouble("weightKg", 0.0).toFloat(),
        reps = json.optInt("reps", 0),
        avgPeakAccel = json.optDouble("avgPeakAccel", 0.0).toFloat()
    )

    private fun bodyCompToJson(entry: BodyCompositionEntry) = JSONObject().apply {
        put("timestamp", entry.timestamp)
        put("skeletalMuscleMassKg", entry.skeletalMuscleMassKg.toDouble())
    }

    private fun jsonToBodyComp(json: JSONObject) = BodyCompositionEntry(
        timestamp = json.optLong("timestamp"),
        skeletalMuscleMassKg = json.optDouble("skeletalMuscleMassKg", 0.0).toFloat()
    )
}
