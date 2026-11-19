package org.jetbrains.bio.qfarm.evolution.stopping

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.bio.qfarm.params.StopCriterionParams
import org.jetbrains.bio.qfarm.params.StopMode
import java.io.BufferedWriter
import java.io.File
import java.time.Instant

/** First line of `stop_calibration.jsonl`: the settings used for the run. */
@Serializable
data class StopCalibrationHeader(
    val type: String = "params",
    val params: StopCriterionParams,
    val createdAt: String
)

/** One line per evolution in `stop_calibration.jsonl`. */
@Serializable
data class StopCalibrationRecord(
    val type: String = "evolution",
    val criterion: String,
    val signalName: String,
    val mode: StopMode,
    val phase: String,
    val attributes: List<String>,
    /** True when the evolution started from a parent front. */
    val seeded: Boolean,
    val populationSize: Int,
    val survivorCount: Int,
    val maxGenerations: Int,
    val generationsRun: Long,
    /** First generation on which the criterion fired; null if it never did. */
    val stopGeneration: Long?,
    /** AUC of this evolution's own front (child front only) at [stopGeneration]. */
    val aucAtStop: Double?,
    /** AUC of this evolution's own front (child front only) at the last generation. */
    val aucFinal: Double?,
    /** Criterion signal per generation, starting at generation 1. */
    val signal: List<Double>,
    /** Hypervolume of the population front in (support / rows, confidence) space, per generation. */
    val hypervolume: List<Double>,
    /** Number of distinct non-dominated objective vectors in the population, per generation. */
    val frontPoints: List<Int>
)

/**
 * Thread-safe writer for `stop_calibration.jsonl`. Evolutions run in parallel,
 * so each record is written as one complete line. Writing is a no-op while closed.
 */
object StopCalibrationLog {

    private val json = Json {
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private var writer: BufferedWriter? = null

    val isOpen: Boolean
        get() = synchronized(this) { writer != null }

    fun open(file: File, params: StopCriterionParams) {
        val header = json.encodeToString(
            StopCalibrationHeader(params = params, createdAt = Instant.now().toString())
        )

        synchronized(this) {
            writer?.close()
            writer = file.bufferedWriter().also {
                it.write(header)
                it.newLine()
                it.flush()
            }
        }
    }

    fun write(record: StopCalibrationRecord) {
        val line = json.encodeToString(record)

        synchronized(this) {
            writer?.let {
                it.write(line)
                it.newLine()
                it.flush()
            }
        }
    }

    fun close() {
        synchronized(this) {
            writer?.close()
            writer = null
        }
    }
}
