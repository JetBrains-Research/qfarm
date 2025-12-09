// OK, see the percentile version (it performs bad), maybe in the future think of a better mutator

package org.jetbrains.bio.qfarm

import io.jenetics.Mutator
import io.jenetics.ext.moea.Vec
import java.util.random.RandomGenerator

class GaussianAttributeMutator(
    probability: Double = hp.probabilityMutation,
    private val stddev: Double = hp.stdMutation
) : Mutator<AttributeGene, Vec<DoubleArray>>(probability) {

    override fun mutate(gene: AttributeGene, random: RandomGenerator): AttributeGene {
        if (!gene.isDefault) {
            val range = gene.max - gene.min
            val noise = rand.nextGaussian() * stddev * range

            val newLower = (gene.lowerBound + noise).coerceIn(gene.min, gene.max)
            val newUpper = (gene.upperBound + noise).coerceIn(newLower, gene.max)

            val mutated = AttributeGene(gene.attributeIndex, newLower, newUpper, gene.min, gene.max)

            return mutated
        }
        return gene
    }
}

//class GaussianAttributeMutator(
//    probability: Double = hp.probabilityMutation,
//    private val stddev: Double = 0.15
//) : Mutator<AttributeGene, Vec<DoubleArray>>(probability) {
//
//    override fun mutate(gene: AttributeGene, random: RandomGenerator): AttributeGene {
//        // Don't mutate default (unused) genes
//        if (gene.isDefault) return gene
//
//        val idx = gene.attributeIndex
//
//        // --- Convert numeric bounds to percentile positions (0–1 range)
//        var lowerP = cumulativePercentage(sortedColumns[idx], gene.lowerBound) / 100.0
//        var upperP = cumulativePercentage(sortedColumns[idx], gene.upperBound) / 100.0
//
//        // --- Add Gaussian noise in percentile space
//        val noise = random.nextGaussian() * stddev
//        lowerP = (lowerP + noise).coerceIn(0.0, 1.0)
//        upperP = (upperP + noise).coerceIn(0.0, 1.0)
//
//        // --- Maintain ordering and minimal width
//        if (upperP < lowerP) upperP = lowerP + 0.01
//        upperP = upperP.coerceAtMost(1.0)
//
//        // --- Convert mutated percentiles back to actual numeric values
//        val newLower = init_cfg.percentile.value(idx, lowerP)
//        val newUpper = init_cfg.percentile.value(idx, upperP)
//
//        val mutated = gene.copy(
//            lowerBound = newLower,
//            upperBound = newUpper
//        )
//
//        return if (mutated.isValid()) mutated else gene
//    }
//}
