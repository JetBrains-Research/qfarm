package org.jetbrains.bio.qfarm.params

import kotlinx.serialization.Serializable

/**
 * What the evolution stop criterion does inside `runEvolution`.
 * In every mode, tree.jsonl reports per node the generations and duration of its evolution.
 */
@Serializable
enum class StopMode {
    /** Criterion is not evaluated; every evolution runs the fixed number of generations. */
    OFF,

    /**
     * Criterion is evaluated every generation and written to `stop_calibration.jsonl`,
     * but every evolution still runs the fixed number of generations.
     */
    LOG_ONLY,

    /** Evolution ends on the generation where the criterion fires (maxGen* stays the cap). */
    STOP
}

/**
 * Settings of the evolution stop criterion, kept in one place.
 * Not exposed on the CLI yet: change the defaults below.
 */
@Serializable
data class StopCriterionParams(
    val mode: StopMode = StopMode.LOG_ONLY,

    /** The criterion cannot fire before this generation. */
    val minGenerations: Int = 20,

    /**
     * Consecutive stagnant generations needed before the criterion fires.
     * Also the number of past survivor sets a survivor is compared with.
     */
    val patience: Int = 3,

    /**
     * Two rules count as the same when, for every attribute, their percentile
     * intervals overlap (intersection / union) at least this much.
     */
    val similarityThreshold: Double = 0.95,

    /** A generation is stagnant when at most this many of its survivors are new. */
    val maxNewSurvivors: Int = 0,
) {
    init {
        require(minGenerations >= 1) { "minGenerations must be >= 1, was $minGenerations" }
        require(patience >= 1) { "patience must be >= 1, was $patience" }
        require(similarityThreshold in 0.0..1.0) {
            "similarityThreshold must be in [0, 1], was $similarityThreshold"
        }
        require(maxNewSurvivors >= 0) { "maxNewSurvivors must be >= 0, was $maxNewSurvivors" }
    }
}

/** Active stop-criterion settings. */
val stopParams = StopCriterionParams()
