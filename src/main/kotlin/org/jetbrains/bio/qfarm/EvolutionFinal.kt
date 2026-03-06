package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq


fun topRange(
    attributes: List<Int>
): ScoredFront {
    val start = System.nanoTime()
    println("\n$PURPLE🏆SEARCHING FOR THE BEST RANGE OF ${attributes.map {idx -> columnNames[idx]}} ... $RESET")
    require(attributes.isNotEmpty()) { "attributes must not be empty." }

    val parentScoredFront: ScoredFront? =
        EvolutionContext.frontStack.lastOrNull()
    val parentFront = parentScoredFront?.front

    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        runEvolution(attributes, popSize = hp.popSizeRange, generationCount = hp.maxGenRange, parentFront = parentFront)

    println("$PURPLE[🏁 Pareto front (all) has ${front.size()} solutions]$RESET")

    if (front.isEmpty) {
        println("$YELLOW[⚠️ No solutions matched the requested attributes. Returning empty result.]$RESET")
        return ScoredFront(front, doubleArrayOf())
    }

    val scores = doubleArrayOf()

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("Range finder: elapsed=%.2fs".format(elapsed))

    return ScoredFront(front, scores)
}
