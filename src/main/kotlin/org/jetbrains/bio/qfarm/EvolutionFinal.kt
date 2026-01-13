package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq

data class TopRangeResult(
    val ranges: List<Int>,
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
)

fun topRange(
    attributes: List<Int>
): TopRangeResult {
    val start = System.nanoTime()
    println("\n$PURPLE🏆SEARCHING FOR THE BEST RANGE OF ${attributes.map {idx -> columnNames[idx]}} ... $RESET")
    require(attributes.isNotEmpty()) { "attributes must not be empty." }

    val parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? =
        EvolutionContext.frontStack.lastOrNull()

    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        runEvolution(attributes, popSize = hp.popSizeRange, generationCount = hp.maxGenRange, parentFront = parentFront)

    println("$PURPLE[🏁 Pareto front (all) has ${front.size()} solutions]$RESET")

    if (front.isEmpty) {
        println("$YELLOW[⚠️ No solutions matched the requested attributes. Returning empty result.]$RESET")
        return TopRangeResult(
            ranges = emptyList(),
            front = front
        )
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("Range finder: elapsed=%.2fs".format(elapsed))

    return TopRangeResult(
        ranges = attributes,
        front = front
    )
}
