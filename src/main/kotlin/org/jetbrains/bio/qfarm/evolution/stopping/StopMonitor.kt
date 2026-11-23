package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.engine.EvolutionResult
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evaluation.fronts.computeFrontScores
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.EvolutionPhase
import org.jetbrains.bio.qfarm.params.StopMode
import org.jetbrains.bio.qfarm.statistics.delong.AUC
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
    private val seeded: Boolean
) {
    private val logging = StopCalibrationLog.isOpen
    private val rows = env.datasetWithHeader.data.size

    private val signal = ArrayList<Double>()
    private val indicator = ArrayList<Double>()
    private val hypervolume = ArrayList<Double>()
    private val frontPoints = ArrayList<Int>()

    private var aucAtStop: Double? = null

    /** First generation on which the criterion fired, or null. */
    var stopGeneration: Long? = null
        private set

    /**
     * Pareto front of the population at [stopGeneration]: the rules an early stop reports.
     * Extracted like the final front of `runEvolution`; null while the criterion has not fired.
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
        }

        if (observation.shouldStop && stopGeneration == null) {
            stopGeneration = result.generation()
            val front = ISeq.of(paretoFrontOf(result.population()))
            stopFront = front
            if (logging) aucAtStop = frontAuc(front)
        }

        return observation.shouldStop
    }

    /** Writes the calibration record for this evolution (no-op when the log is closed). */
    fun finish(mode: StopMode) {
        if (!logging) return
        val last = lastResult ?: return

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
                aucFinal = frontAuc(ISeq.of(paretoFrontOf(last.population()))),
                signal = signal,
                indicator = indicator,
                hypervolume = hypervolume,
                frontPoints = frontPoints
            )
        )
    }

    private fun frontAuc(front: ISeq<RulePhenotype>): Double? {
        val labels = env.datasetWithHeader.labels
        if (labels.size != rows) return null
        if (front.isEmpty) return null

        return AUC.compute(labels, computeFrontScores(front, env))
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
