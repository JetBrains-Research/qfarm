package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.engine.EvolutionResult
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.EvolutionPhase
import org.jetbrains.bio.qfarm.params.StopMode
import org.jetbrains.bio.qfarm.util.paretoFrontOf

/**
 * Runs a [StopCriterion] for one evolution: remembers the first generation on which it fired
 * and the Pareto front of that generation (the rules an early stop reports), and, when
 * [StopCalibrationLog] is open, writes one calibration record at the end.
 *
 * It never uses the random generator, so it does not change the evolution.
 */
class StopMonitor(
    private val criterion: StopCriterion,
    private val env: EvolutionEnvironment,
    private val phase: EvolutionPhase,
    private val attributes: List<Int>,
    private val populationSize: Int,
    private val maxGenerations: Int,
    private val seeded: Boolean,
    /** Own-front AUC every this many generations (analysis only); 0 = only at the stop and at the end. */
    private val aucEvery: Int = 0,
    /**
     * Keep the Pareto front of the stop generation ([stopFront]). Needed in LOG_ONLY, where the
     * evolution goes on; in STOP the final front is that front, so it is not computed twice.
     */
    private val keepStopFront: Boolean = true
) {
    private val logging = StopCalibrationLog.isOpen
    private val rows = env.datasetWithHeader.data.size

    private val signal = ArrayList<Double>()
    private val indicator = ArrayList<Double>()
    private val hypervolume = ArrayList<Double>()
    private val frontPoints = ArrayList<Int>()
    private val evolveSeconds = ArrayList<Double>()

    // Analysis only: fronts as rule keys, turned into AUC values in finish(), after the evolution.
    private val sampledFronts = ArrayList<Pair<Int, List<RuleKey>>>()   // (index in generation lists, front)
    private var stopKeys: List<RuleKey>? = null
    private var lastKeys: List<RuleKey> = emptyList()

    /**
     * Time spent on analysis-only work (collecting fronts for the AUC, computing it).
     * runEvolution subtracts it from the evolution's wall time.
     */
    var analysisNanos = 0L
        private set

    /** First generation on which the criterion fired, or null. */
    var stopGeneration: Long? = null
        private set

    /**
     * Pareto front of the population at [stopGeneration]: the rules an early stop reports.
     * Extracted like the final front of `runEvolution`; null while the criterion has not fired,
     * and not kept in STOP (see keepStopFront).
     */
    var stopFront: ISeq<RulePhenotype>? = null
        private set

    /** Last generation seen by [observe]. */
    var lastResult: EvolutionResult<AttributeGene, Vec<DoubleArray>>? = null
        private set

    /** Feeds one generation to the criterion. Returns true when it says stop. */
    fun observe(result: EvolutionResult<AttributeGene, Vec<DoubleArray>>): Boolean {
        lastResult = result

        val observation = criterion.update(result.generation(), result.population())

        if (logging) {
            signal += observation.signal
            indicator += observation.indicator

            val front = frontSummary(result.population(), rows)
            hypervolume += front.hypervolume
            frontPoints += front.points
            evolveSeconds += Math.round(result.durations().evolveDuration().toNanos() / 1e3) / 1e6
        }

        val firesNow = observation.shouldStop && stopGeneration == null
        if (firesNow) {
            stopGeneration = result.generation()
            if (keepStopFront || logging) stopFront = ISeq.of(paretoFrontOf(result.population()))
        }

        if (logging) {
            val t0 = System.nanoTime()
            val keys = FrontAucTracker.keys(stopFront.takeIf { firesNow } ?: ISeq.of(paretoFrontOf(result.population())))
            lastKeys = keys
            if (firesNow) stopKeys = keys
            val g = result.generation()
            if (aucEvery > 0 && (g == 1L || g % aucEvery == 0L)) sampledFronts += (signal.size - 1) to keys
            analysisNanos += System.nanoTime() - t0
        }

        return observation.shouldStop
    }

    /**
     * Writes the calibration record for this evolution (no-op when the log is closed).
     * Call after the evolution's time was taken: the AUC values are computed here.
     * [seconds] is the evolution's wall time without analysis, [engineSeconds] its Jenetics engine time.
     */
    fun finish(mode: StopMode, seconds: Double, engineSeconds: Double) {
        if (!logging) return
        val last = lastResult ?: return

        val t0 = System.nanoTime()
        val tracker = FrontAucTracker(env)
        val auc = MutableList(signal.size) { Double.NaN }
        for ((i, keys) in sampledFronts) auc[i] = tracker.auc(keys) ?: Double.NaN
        val aucAtStop = stopKeys?.let { FrontAucTracker(env).auc(it) }
        val aucFinal = FrontAucTracker(env).auc(lastKeys)
        analysisNanos += System.nanoTime() - t0

        StopCalibrationLog.write(
            StopCalibrationRecord(
                criterion = criterion.name,
                signalName = criterion.signalName,
                indicatorName = criterion.indicatorName,
                mode = mode,
                phase = phase.name,
                attributes = attributes.map { env.columnNames.getOrElse(it) { idx -> "attr#$idx" } },
                seeded = seeded,
                populationSize = populationSize,
                maxGenerations = maxGenerations,
                generationsRun = last.generation(),
                stopGeneration = stopGeneration,
                aucAtStop = aucAtStop,
                aucFinal = aucFinal,
                seconds = seconds,
                evolveSeconds = engineSeconds,
                analysisSeconds = analysisNanos / 1e9,
                signal = signal,
                indicator = indicator,
                hypervolume = hypervolume,
                frontPoints = frontPoints,
                evolveSecondsPerGeneration = evolveSeconds,
                auc = auc
            )
        )
    }
}

internal data class FrontSummary(val hypervolume: Double, val points: Int)

/**
 * Hypervolume (reference point (0, 0)) and number of distinct non-dominated points of a
 * population in (support / rows, confidence) space, both maximised.
 */
internal fun frontSummary(population: ISeq<RulePhenotype>, rows: Int): FrontSummary =
    frontSummary(population.asList().map { it.fitness().data() }, rows)

internal fun frontSummary(points: List<DoubleArray>, rows: Int): FrontSummary {
    require(rows > 0)
    val front = firstFront(points)          // support descending, confidence ascending
    var area = 0.0
    var previous = 0.0
    for (p in front) {
        area += (p[0] / rows) * (p[1].coerceAtLeast(0.0) - previous)
        previous = p[1].coerceAtLeast(0.0)
    }
    return FrontSummary(Math.round(area * 1e6) / 1e6, front.size)
}
