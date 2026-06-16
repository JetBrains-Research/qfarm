package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Genotype
import org.jetbrains.bio.qfarm.core.AttributeGene

fun evaluateRule(
    genotype: Genotype<AttributeGene>,
    oracle: CountingKdTreeOracle
): DoubleArray {
    val stats = oracle.evaluate(genotype)

    return doubleArrayOf(
        stats.support.toDouble(),
        stats.confidence
    )
}
