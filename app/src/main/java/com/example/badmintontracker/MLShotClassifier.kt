package com.example.badmintontracker

import android.content.Context
import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Optional ML upgrade for shot classification — same job as ShotClassifier
 * (bucket a ShotEvent into SMASH / CLEAR / DROP / UNKNOWN), but backed by a
 * small on-device TensorFlow Lite model you train yourself on your own
 * labeled swings instead of hand-picked thresholds. See README.md section 3
 * ("ML upgrade") and tools/train_shot_classifier.py.
 *
 * This is intentionally NOT wired in as a hard dependency — [tryLoad]
 * returns null if `shot_classifier.tflite` isn't present under
 * app/src/main/assets/ (e.g. you haven't trained one yet), and
 * MainActivity falls back to the rule-based ShotClassifier in that case.
 * That means this file (and the tflite dependency) can sit here unused
 * with zero effect on the app until you actually add a trained model.
 *
 * Feature order fed to the model MUST match training exactly:
 *   [peakAccelMagnitude, peakGyroMagnitude, durationMillis, verticalRatio]
 * — same four features ShotClassifier already uses, same order as
 * FEATURES in the training script. Output is a 4-way softmax in the same
 * order as [LABELS] below.
 *
 * Serve detection (ServeDetector.kt) doesn't touch this file — it's a
 * separate flag computed from ShotEvent.precededByStillnessMillis, which
 * this model was never trained on. That's intentional: it means adding
 * serve detection needed zero retraining here.
 */
class MLShotClassifier private constructor(
    private val interpreter: Interpreter
) {
    fun classify(shot: SmashDetector.ShotEvent): ShotType {
        val input = arrayOf(
            floatArrayOf(
                shot.peakAccelMagnitude,
                shot.peakGyroMagnitude,
                shot.durationMillis.toFloat(),
                shot.verticalRatio
            )
        )
        val output = Array(1) { FloatArray(LABELS.size) }
        interpreter.run(input, output)

        val probs = output[0]
        val bestIndex = probs.indices.maxByOrNull { probs[it] } ?: return ShotType.UNKNOWN
        // Below this confidence, prefer an honest UNKNOWN over a shaky guess —
        // tune CONFIDENCE_THRESHOLD against your own validation numbers.
        return if (probs[bestIndex] >= CONFIDENCE_THRESHOLD) LABELS[bestIndex] else ShotType.UNKNOWN
    }

    fun close() = interpreter.close()

    companion object {
        private const val MODEL_ASSET = "shot_classifier.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.55f

        // Order MUST match LABELS in tools/train_shot_classifier.py exactly —
        // this is what maps the model's output index back to a ShotType.
        private val LABELS = listOf(ShotType.SMASH, ShotType.CLEAR, ShotType.DROP, ShotType.UNKNOWN)

        /**
         * Returns null (never throws) if the model asset is missing or fails
         * to load — callers should treat that as "no ML model yet" and fall
         * back to ShotClassifier, not as a crash.
         */
        fun tryLoad(context: Context): MLShotClassifier? {
            return try {
                val buffer = loadModelFile(context.assets, MODEL_ASSET)
                MLShotClassifier(Interpreter(buffer))
            } catch (e: Exception) {
                null
            }
        }

        private fun loadModelFile(assets: AssetManager, filename: String): MappedByteBuffer {
            val fd = assets.openFd(filename)
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }
}
