package org.jetbrains.bio.qfarm

import io.jenetics.Mutator
import io.jenetics.ext.moea.Vec
import java.util.random.RandomGenerator

class GaussianAttributeMutator(
    probability: Double = hp.probabilityMutation,
    private val fixedAttributeIndices: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    private val stddev: Double = hp.stdMutation
) : Mutator<AttributeGene, Vec<DoubleArray>>(probability) {

    override fun mutate(gene: AttributeGene, random: RandomGenerator): AttributeGene {
        // is not default covers additions in random front, while fixed attr the ones in final front,
        // as parent front passes the rules with default additions
        // TODO: what about the search attributes in random front? Rn there should be no way for them to appear after parent front initialization of the initial population...
        if (!gene.isDefault || gene.attributeIndex in fixedAttributeIndices.map { it -> it.first }) {

            val range = gene.max - gene.min
            val noise = random.nextGaussian() * stddev * range

            val newLower = (gene.lowerBound + noise).coerceIn(gene.min, gene.max)
            val newUpper = (gene.upperBound + noise).coerceIn(newLower, gene.max)

            return gene.copy(
                lowerBound = newLower,
                upperBound = newUpper
            )
        }

        return gene
    }
}
