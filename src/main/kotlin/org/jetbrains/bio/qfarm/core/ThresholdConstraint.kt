package org.jetbrains.bio.qfarm.core

import io.jenetics.Genotype
import io.jenetics.Phenotype
import io.jenetics.engine.Constraint
import io.jenetics.ext.moea.Vec
import io.jenetics.util.Factory
import org.jetbrains.bio.qfarm.evaluation.TinSpinRangeEvaluationOracle
import org.jetbrains.bio.qfarm.params.hp
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Fast constraint that enforces min/max support on the antecedent (X) only.
 * Avoids full metric evaluation, no boxing, thread-safe, and minimizes allocations.
 */
class SupportThresholdConstraint(
    private val genotypeFactory: Factory<Genotype<AttributeGene>>,
    private val oracle: TinSpinRangeEvaluationOracle,
    private val bounds: Array<DoubleArray>,
    private val minSupport: Int = hp.minSupport,
    private val maxSupport: Int = hp.maxSupport,
    private val maxAttempts: Int = 10
) : Constraint<AttributeGene, Vec<DoubleArray>> {

    companion object {
        private val attemptCounts = AtomicIntegerArray(11) // 1..10

        fun printAttemptStatistics() {
            println("Total repair attempt statistics:")
            for (i in 1 until attemptCounts.length()) {
                println("$i attempts: ${attemptCounts.get(i)}")
            }
        }

        fun resetAttemptStatistics() {
            for (i in 0 until attemptCounts.length()) {
                attemptCounts.set(i, 0)
            }
        }
    }

    override fun test(individual: Phenotype<AttributeGene, Vec<DoubleArray>>): Boolean {
        val sx = if (individual.isEvaluated) {
            individual.fitness().data()[0].toInt()
        } else {
            oracle.supportOf(individual.genotype(), bounds)
        }

        return sx in minSupport..maxSupport
    }

    override fun repair(
        individual: Phenotype<AttributeGene, Vec<DoubleArray>>,
        generation: Long
    ): Phenotype<AttributeGene, Vec<DoubleArray>> {

        val sx0 = if (individual.isEvaluated) {
            individual.fitness().data()[0].toInt()
        } else {
            oracle.supportOf(individual.genotype(), bounds)
        }

        var bestGenotype: Genotype<AttributeGene>? = null
        var bestGap = Int.MAX_VALUE
        var attempts = 0

        while (attempts < maxAttempts) {
            val candidate = genotypeFactory.newInstance()
            val sx = oracle.supportOf(candidate, bounds)

            if (sx in minSupport..maxSupport) {
                attemptCounts.incrementAndGet(attempts + 1)
                return Phenotype.of(candidate, generation)
            }

            val gap = gapToRange(sx, minSupport, maxSupport)

            if (gap < bestGap) {
                bestGap = gap
                bestGenotype = candidate
            }

            attempts++
        }


        return if (bestGenotype != null && gapToRange(sx0, minSupport, maxSupport) > bestGap) {
            Phenotype.of(bestGenotype, generation)
        } else {
            individual
        }
    }
}

/** Distance from x to [lo, hi]; zero if inside. */
private fun gapToRange(x: Int, lo: Int, hi: Int): Int =
    when {
        x < lo -> lo - x
        x > hi -> x - hi
        else -> 0
    }
