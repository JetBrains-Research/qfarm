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

            val newCenter = (center + random.nextGaussian() * stddev)
                .coerceIn(0.0, 1.0)

            val newWidth = (width * (1 + random.nextGaussian() * 0.2))
                .coerceIn(1e-4, 1.0)

            val newPL = (newCenter - newWidth / 2).coerceIn(0.0, 1.0)
            val newPR = (newCenter + newWidth / 2).coerceIn(0.0, 1.0)

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


//class PercentileAttributeMutator(
//    probability: Double = hp.probabilityMutation,
//    private val fixedAttributeIndices: List<Int>,
//    private val stddev: Double = hp.stdMutation
//) : Mutator<AttributeGene, Vec<DoubleArray>>(probability) {
//
//    override fun mutate(gene: AttributeGene, random: RandomGenerator): AttributeGene {
//
//        // Only mutate active or fixed genes
//        if (!gene.isDefault || gene.attributeIndex in fixedAttributeIndices) {
//
//            val pL = gene.pLeft
//            val pR = gene.pRight
//
//            val center = (pL + pR) / 2.0
//            val width = (pR - pL)
//
//            // --- Mutation mode selection ---
//            val mode = random.nextDouble()
//
//            val (newPL, newPR) = when {
//
//                // ======================
//                // 1. SHIFT (70%)
//                // ======================
//                mode < 0.7 -> {
//                    val shift = random.nextGaussian() * stddev
//                    val newCenter = (center + shift).coerceIn(0.0, 1.0)
//
//                    val half = width / 2.0
//                    val lo = (newCenter - half).coerceIn(0.0, 1.0)
//                    val hi = (newCenter + half).coerceIn(0.0, 1.0)
//
//                    lo to hi
//                }
//
//                // ======================
//                // 2. EXPAND (15%)
//                // ======================
//                mode < 0.85 -> {
//                    val factor = 1 + kotlin.math.abs(random.nextGaussian()) * 0.2
//                    val newWidth = (width * factor).coerceAtMost(1.0)
//
//                    val half = newWidth / 2.0
//                    val lo = (center - half).coerceIn(0.0, 1.0)
//                    val hi = (center + half).coerceIn(0.0, 1.0)
//
//                    lo to hi
//                }
//
//                // ======================
//                // 3. CONTRACT (15%)
//                // ======================
//                else -> {
//                    val factor = 1 - kotlin.math.abs(random.nextGaussian()) * 0.3
//                    val newWidth = (width * factor).coerceAtLeast(1e-4)
//
//                    val half = newWidth / 2.0
//                    val lo = (center - half).coerceIn(0.0, 1.0)
//                    val hi = (center + half).coerceIn(0.0, 1.0)
//
//                    lo to hi
//                }
//            }
//
//            // --- Map percentiles → actual values ---
//            val newLower = gene.cfg.percentile.value(gene.attributeIndex, newPL)
//            val newUpper = gene.cfg.percentile.value(gene.attributeIndex, newPR)
//
//            return gene.copy(
//                pLeft = newPL,
//                pRight = newPR,
//                lowerBound = newLower,
//                upperBound = newUpper
//            )
//        }
//
//        return gene
//    }
//}
