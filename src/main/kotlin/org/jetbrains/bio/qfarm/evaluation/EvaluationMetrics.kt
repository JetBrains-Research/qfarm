package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Genotype
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome

fun evaluateRule(
    genotype: Genotype<AttributeGene>,
    oracle: LuceneRangeEvaluationOracle,
    globalBounds: Array<DoubleArray>
): DoubleArray {

    val lhs = genotype[0] as RuleSideChromosome

    val min = DoubleArray(oracle.dims)
    val max = DoubleArray(oracle.dims)

    for ((localDim, originalAttrIndex) in oracle.attributes.withIndex()) {
        min[localDim] = globalBounds[originalAttrIndex][0]
        max[localDim] = globalBounds[originalAttrIndex][1]
    }

    var hasActiveGene = false

    for (i in 0 until lhs.length()) {
        val g = lhs[i]

        if (!g.isDefault) {
            hasActiveGene = true

            val localDim = oracle.localDimensionOf(g.attributeIndex)

            min[localDim] = g.lowerBound
            max[localDim] = g.upperBound
        }
    }

    if (!hasActiveGene) {
        return doubleArrayOf(0.0, 0.0)
    }

    val stats = oracle.evaluate(
        LocalHyperRectangle(
            min = min,
            max = max
        )
    )

    return doubleArrayOf(
        stats.support.toDouble(),
        stats.confidence
    )
}
