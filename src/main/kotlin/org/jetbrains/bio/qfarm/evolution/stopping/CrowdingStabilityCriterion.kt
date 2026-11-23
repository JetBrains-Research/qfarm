package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.params.StopCriterionParams
import kotlin.math.sqrt

/**
 * Distinct non-dominated objective vectors of a population, both objectives maximised,
 * returned with the first objective descending (and so the second ascending).
 *
 * QFARM rules have two objectives (support, confidence), which allows an O(n log n) sweep.
 * Points with identical objective values are kept once: different intervals often give
 * exactly the same support and confidence, and a duplicate adds nothing to the front's spread.
 */
internal fun firstFront(points: List<DoubleArray>): List<DoubleArray> {
    require(points.all { it.size == 2 }) { "firstFront expects two objectives" }

    val order = points.sortedWith(compareByDescending<DoubleArray> { it[0] }.thenByDescending { it[1] })
    val front = ArrayList<DoubleArray>()
    var best = Double.NEGATIVE_INFINITY
    for (p in order) {
        if (p[1] > best) {
            front += p
            best = p[1]
        }
    }
    return front
}

/**
 * NSGA-II crowding distance of every point of a front, as in Jenetics' `Pareto.crowdingDistance`:
 * per objective, the gap between a point's two neighbours divided by that objective's range on
 * the front, summed over the objectives. The two extreme points of each objective get infinity;
 * with fewer than three points every point does.
 */
internal fun crowdingDistances(front: List<DoubleArray>): DoubleArray {
    val n = front.size
    val result = DoubleArray(n)
    if (n < 3) {
        result.fill(Double.POSITIVE_INFINITY)
        return result
    }
    for (m in front[0].indices) {
        val idx = (0 until n).sortedByDescending { front[it][m] }
        result[idx.first()] = Double.POSITIVE_INFINITY
        result[idx.last()] = Double.POSITIVE_INFINITY
        val range = front[idx.first()][m] - front[idx.last()][m]
        if (range > 0.0) {
            for (k in 1 until n - 1) {
                result[idx[k]] += (front[idx[k - 1]][m] - front[idx[k + 1]][m]) / range
            }
        }
    }
    return result
}

/**
 * Largest finite crowding distance on the first front: d(l) in Rudenko and Schoenauer.
 * NaN when the front has fewer than three distinct points (no interior point exists).
 */
internal fun maxCrowding(points: List<DoubleArray>): Double =
    crowdingDistances(firstFront(points)).filter { it.isFinite() }.maxOrNull() ?: Double.NaN

/**
 * Rudenko and Schoenauer (2004): stop NSGA-II once the largest crowding distance of the first
 * front has stabilised.
 *
 * Each generation, d(l) is the largest finite crowding distance among the distinct
 * non-dominated points of the population. The criterion keeps the last `windowLength` values
 * and fires when their standard deviation, sqrt(1/L sum (d(l) - mean)^2) as in the paper, is at
 * most `maxStdDev`, but not before `minGenerations`.
 *
 * A generation where d(l) is undefined (front with fewer than three distinct points) clears
 * the window: stability has to be shown over L consecutive generations.
 *
 * Signal: the standard deviation over the window (NaN until the window is full).
 * Indicator: d(l).
 */
class CrowdingStabilityCriterion(
    private val params: StopCriterionParams
) : StopCriterion {

    override val name: String = "crowding-stability"
    override val signalName: String = "stdMaxCrowding"
    override val indicatorName: String = "maxCrowding"

    private val window = ArrayDeque<Double>()

    override fun update(generation: Long, population: ISeq<RulePhenotype>): StopObservation =
        updateIndicator(generation, maxCrowding(population.asList().map { it.fitness().data() }))

    fun updateIndicator(generation: Long, d: Double): StopObservation {
        if (d.isNaN()) {
            window.clear()
        } else {
            window.addLast(d)
            while (window.size > params.windowLength) window.removeFirst()
        }

        val full = window.size == params.windowLength
        val sd = if (full) populationStdDev(window) else Double.NaN
        val shouldStop = full && generation >= params.minGenerations && sd <= params.maxStdDev

        return StopObservation(signal = sd, indicator = d, shouldStop = shouldStop)
    }
}

internal fun populationStdDev(values: Collection<Double>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}
