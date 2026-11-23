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
 *
 * Criterion: Rudenko and Schoenauer (2004), "A steady performance stopping criterion for
 * Pareto-based evolutionary algorithms". d(l) is the largest crowding distance among the
 * first-front individuals of generation l; the evolution stops once the standard deviation
 * of d over the last [windowLength] generations is at most [maxStdDev].
 * The paper used windowLength 40 and maxStdDev 0.02 with population 100.
 */
@Serializable
data class StopCriterionParams(
    val mode: StopMode = StopMode.LOG_ONLY,

    /** Name written to the outputs, so runs with different criteria can be told apart. */
    val criterion: String = "crowding-stability",

    /** L: number of consecutive generations over which d(l) must be stable. */
    val windowLength: Int = 40,

    /** delta_lim: the criterion fires when the standard deviation of d over the window is at most this. */
    val maxStdDev: Double = 0.02,

    /** The criterion cannot fire before this generation (the window also needs [windowLength] values). */
    val minGenerations: Int = 40,
) {
    init {
        require(windowLength >= 2) { "windowLength must be >= 2, was $windowLength" }
        require(maxStdDev >= 0.0) { "maxStdDev must be >= 0, was $maxStdDev" }
        require(minGenerations >= 1) { "minGenerations must be >= 1, was $minGenerations" }
    }
}

/** Active stop-criterion settings. */
val stopParams = StopCriterionParams()
