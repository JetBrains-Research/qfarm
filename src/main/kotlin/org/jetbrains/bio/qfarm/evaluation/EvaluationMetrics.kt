package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Genotype
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.datasetWithHeader

fun evaluateRule(
    genotype: Genotype<AttributeGene>,
    oracle: LuceneRangeEvaluationOracle
): DoubleArray {

    val lhs = genotype[0] as RuleSideChromosome

    val indices = mutableListOf<Int>()
    val lows = mutableListOf<Double>()
    val ups = mutableListOf<Double>()

    for (i in 0 until lhs.length()) {
        val g = lhs[i]

        if (!g.isDefault) {
            indices += g.attributeIndex
            lows += g.lowerBound
            ups += g.upperBound
        }
    }

    if (indices.isEmpty()) {
        return doubleArrayOf(0.0, 0.0)
    }

    val stats = oracle.evaluate(
        HyperRectangle(
            indices = indices.toIntArray(),
            min = lows.toDoubleArray(),
            max = ups.toDoubleArray()
        )
    )

    return doubleArrayOf(
        stats.support.toDouble(),
        stats.confidence
    )
}
