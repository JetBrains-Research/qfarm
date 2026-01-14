package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import io.jenetics.Phenotype
import io.jenetics.engine.Constraint
import io.jenetics.ext.moea.Vec
import io.jenetics.util.Factory

/**
 * Fast constraint that enforces min/max support on the antecedent (X) only.
 * Avoids full metric evaluation, no boxing, thread-safe, and minimizes allocations.
 */
class SupportThresholdConstraint(
    private val genotypeFactory: Factory<Genotype<AttributeGene>>,
    private val data: List<DoubleArray> = datasetWithHeader.data,
    private val minSupport: Int = hp.minSupport,
    private val maxSupport: Int = hp.maxSupport,
    private val maxAttempts: Int = 10
) : Constraint<AttributeGene, Vec<DoubleArray>> {

    override fun test(individual: Phenotype<AttributeGene, Vec<DoubleArray>>): Boolean {
        val sx = if (individual.isEvaluated) {
            individual.fitness().data()[0].toInt()
        } else {
            supportXOf(individual.genotype(), data)
        }
        return sx in minSupport..maxSupport
    }

    override fun repair(
        individual: Phenotype<AttributeGene, Vec<DoubleArray>>,
        generation: Long
    ): Phenotype<AttributeGene, Vec<DoubleArray>> {
        // Try up to maxAttempts new candidates from the factory.
        val sx0 = if (individual.isEvaluated) {
            individual.fitness().data()[0].toInt()
        } else {
            supportXOf(individual.genotype(), data)
        }

        // Track the best (closest) candidate so we can still improve feasibility if none perfect.
        var bestGenotype: Genotype<AttributeGene>? = null
        var bestGap = Int.MAX_VALUE
        var attempts = 0

        while (attempts < maxAttempts) {
            val candidate = genotypeFactory.newInstance()
            val sx = supportXOf(candidate, data)

            if (sx in minSupport..maxSupport) {
                return Phenotype.of(candidate, generation)
            }

            val gap = gapToRange(sx, minSupport, maxSupport)
            if (gap < bestGap) {
                bestGap = gap
                bestGenotype = candidate
            }
            attempts++
        }

        // If we didn't find a feasible one, return the closest candidate if it's better than original.
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

/**
 * Ultra-fast antecedent-only support counter.
 * - No allocations
 * - Early exit on first failing gene
 * - Handles k==0 (no active antecedents): supportX == rows
 */
private fun supportXOf(genotype: Genotype<AttributeGene>, data: List<DoubleArray>): Int {
    val rows = data.size
    if (rows == 0) return 0

    // First chromosome assumed to be antecedent.
    val lhs = genotype[0] as RuleSideChromosome
    val nGenes = lhs.length()

    // Count active genes.
    var k = 0
    var i = 0
    while (i < nGenes) {
        if (!lhs[i].isDefault) k++
        i++
    }

    // X is tautologically true when no active antecedents.
    if (k == 0) return rows

    // Structure-of-arrays for tight inner loop.
    val idxs = IntArray(k)
    val lows = DoubleArray(k)
    val ups  = DoubleArray(k)

    var j = 0
    i = 0
    while (i < nGenes) {
        val g = lhs[i]
        if (!g.isDefault) {
            idxs[j] = g.attributeIndex
            lows[j] = g.lowerBound
            ups[j]  = g.upperBound
            j++
        }
        i++
    }

    var supportX = 0
    var r = 0
    while (r < rows) {
        val row = data[r]
        var ok = true
        j = 0
        while (j < k) {
            val v = row[idxs[j]]
            if (v.isNaN() || v < lows[j] || v > ups[j]) {
                ok = false
                break
            }
            j++
        }
        if (ok) supportX++
        r++
    }
    return supportX
}
