package org.jetbrains.bio.qfarm.core

import io.jenetics.Mutator
import io.jenetics.ext.moea.Vec
import org.jetbrains.bio.qfarm.util.hp
import java.util.random.RandomGenerator

class PercentileAttributeMutator(
    probability: Double = hp.probabilityMutation,
    private val fixedAttributeIndices: List<Int>,
    private val stddev: Double = hp.stdMutation
) : Mutator<AttributeGene, Vec<DoubleArray>>(probability) {

    override fun mutate(gene: AttributeGene, random: RandomGenerator): AttributeGene {

        if (!gene.isDefault || gene.attributeIndex in fixedAttributeIndices) {

            // --- 1. mutate percentile space ---
            val center = (gene.pLeft + gene.pRight) / 2.0
            val width = (gene.pRight - gene.pLeft)

            val newWidth = (width * (1 + random.nextGaussian() * 0.2))
                .coerceIn(1e-4, hp.maxWidth)

            val newCenter = (center + random.nextGaussian() * stddev)
                .coerceIn(newWidth / 2.0, 1.0 - newWidth / 2.0)

            val newPL = (newCenter - newWidth / 2)
            val newPR = (newCenter + newWidth / 2)

            // --- 2. map to values ---
            val newLower = gene.cfg.percentile.value(gene.attributeIndex, newPL)
            val newUpper = gene.cfg.percentile.value(gene.attributeIndex, newPR)

            return gene.copy(
                pLeft = newPL,
                pRight = newPR,
                lowerBound = newLower,
                upperBound = newUpper
            )
        }

        return gene
    }
}
