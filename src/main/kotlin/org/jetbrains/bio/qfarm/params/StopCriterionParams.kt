package org.jetbrains.bio.qfarm.params

import kotlinx.serialization.Serializable
import kotlin.math.max

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
 * of d over the last [windowLength] generations is at most the limit.
 *
 * The level where that standard deviation settles falls as about 5 / population, so the limit
 * follows the evolution's population: limit = max([maxStdDev], [limitPerIndividual] / population),
 * see [limitFor]. With the defaults: 0.07 at population 100, 0.035 at 200, 0.02 from 350 up.
 * Tuning: `.claude/stop_param_tuning/final_report/` (window 40, constant 7, Friedman1 and two real datasets).
 */
@Serializable
data class StopCriterionParams(

    // ---- what the criterion does -----------------------------------------------------------

    /** OFF, LOG_ONLY or STOP (see [StopMode]). */
    val mode: StopMode = StopMode.LOG_ONLY,

    /** Name written to the outputs, so runs with different criteria can be told apart. */
    val criterion: String = "crowding-stability",

    // ---- window ----------------------------------------------------------------------------

    /** L: number of consecutive generations over which d(l) must be stable. */
    val windowLength: Int = 40,

    /** The criterion cannot fire before this generation (the window also needs [windowLength] values). */
    val minGenerations: Int = 40,

    // ---- limit (delta_lim) -------------------------------------------------------------------

    /**
     * Fixed part of the limit and its lower bound: the criterion fires when the standard deviation
     * of d over the window is at most [limitFor] (population), which is never below this value.
     */
    val maxStdDev: Double = 0.02,

    /**
     * Population-scaled part of the limit: limit = max([maxStdDev], limitPerIndividual / population).
     * 7 is the smallest constant that fired in every tuning run; 10 to 15 behaved the same.
     * 0 = fixed limit [maxStdDev] for every population.
     */
    val limitPerIndividual: Double = 7.0,

    // ---- analysis only -----------------------------------------------------------------------

    /**
     * ANALYSIS ONLY, temporary: in LOG_ONLY and STOP, write the own-front AUC of every this many
     * generations (and of generation 1) to `stop_calibration.jsonl`. It is computed after each
     * evolution has ended and its time is not counted in the evolution's seconds. 0 = off
     * (the AUC at the stop and at the end is still written).
     */
    val aucEveryGenerations: Int = 1,
) {
    init {
        require(windowLength >= 2) { "windowLength must be >= 2, was $windowLength" }
        require(minGenerations >= 1) { "minGenerations must be >= 1, was $minGenerations" }
        require(maxStdDev >= 0.0) { "maxStdDev must be >= 0, was $maxStdDev" }
        require(limitPerIndividual >= 0.0) { "limitPerIndividual must be >= 0, was $limitPerIndividual" }
        require(aucEveryGenerations >= 0) { "aucEveryGenerations must be >= 0, was $aucEveryGenerations" }
    }

    /** Limit for an evolution with population [popSize]: max([maxStdDev], [limitPerIndividual] / popSize). */
    fun limitFor(popSize: Int): Double {
        require(popSize > 0) { "popSize must be > 0, was $popSize" }
        return max(maxStdDev, limitPerIndividual / popSize)
    }
}

/** Active stop-criterion settings. */
val stopParams = StopCriterionParams()
