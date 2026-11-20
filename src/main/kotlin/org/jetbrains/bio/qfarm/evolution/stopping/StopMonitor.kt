package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.Optimize
import io.jenetics.Selector
import io.jenetics.engine.EvolutionResult
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import io.jenetics.util.Seq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evaluation.fronts.computeFrontScores
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.EvolutionPhase
import org.jetbrains.bio.qfarm.params.StopMode
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.util.paretoFrontOf

/**
 * Survivors selector that remembers the survivors it picked last.
 *
 * `EvolutionResult` does not expose the survivor set, so wrapping the selector
 * gives exactly the set the engine kept, at no extra cost. Jenetics calls the
 * survivors selector once per generation, before the generation's result is built.
 */
class SurvivorRecorder(
    private val delegate: Selector<AttributeGene, Vec<DoubleArray>>
) : Selector<AttributeGene, Vec<DoubleArray>> {

    @Volatile
    var lastSelected: ISeq<RulePhenotype> = ISeq.empty()
        private set

    override fun select(
        population: Seq<RulePhenotype>,
        count: Int,
        opt: Optimize
    ): ISeq<RulePhenotype> =
        delegate.select(population, count, opt).also { lastSelected = it }

    override fun toString(): String = "SurvivorRecorder[$delegate]"
}

/**
 * Runs a [StopCriterion] for one evolution and, when [StopCalibrationLog] is
 * open, collects the per-generation calibration data and writes one record at
 * the end. It never uses the random generator, so it does not change the evolution.
 */
class StopMonitor(
    private val criterion: StopCriterion,
    private val survivors: SurvivorRecorder,
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

        val observation = criterion.update(
            generation = result.generation(),
            survivors = survivors.lastSelected,
            population = result.population()
        )

        if (logging) {
            signal += observation.signal

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
                mode = mode,
                phase = phase.name,
                attributes = attributes.map { env.columnNames.getOrElse(it) { idx -> "attr#$idx" } },
                seeded = seeded,
                populationSize = populationSize,
                survivorCount = survivors.lastSelected.size(),
                maxGenerations = maxGenerations,
                generationsRun = last.generation(),
                stopGeneration = stopGeneration,
                aucAtStop = aucAtStop,
                aucFinal = frontAuc(ISeq.of(paretoFrontOf(last.population()))),
                signal = signal,
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

internal fun frontSummary(population: ISeq<RulePhenotype>, rows: Int): FrontSummary =
    frontSummary(
        supports = DoubleArray(population.size()) { population[it].fitness().data()[0] },
        confidences = DoubleArray(population.size()) { population[it].fitness().data()[1] },
        rows = rows
    )

/**
 * Hypervolume (reference point (0, 0)) and number of distinct non-dominated
 * points in (support / rows, confidence) space, both maximised. O(n log n).
 */
internal fun frontSummary(supports: DoubleArray, confidences: DoubleArray, rows: Int): FrontSummary {
    require(supports.size == confidences.size)
    require(rows > 0)

    // Highest support first; for equal support, highest confidence first.
    val order = supports.indices.sortedWith(
        compareByDescending<Int> { supports[it] }.thenByDescending { confidences[it] }
    )

    var bestConfidence = Double.NEGATIVE_INFINITY
    var area = 0.0
    var points = 0

    for (i in order) {
        val confidence = confidences[i]
        if (confidence > bestConfidence) {
            area += (supports[i] / rows) *
                    (confidence.coerceAtLeast(0.0) - bestConfidence.coerceAtLeast(0.0))
            bestConfidence = confidence
            points++
        }
    }

    return FrontSummary(Math.round(area * 1e6) / 1e6, points)
}
