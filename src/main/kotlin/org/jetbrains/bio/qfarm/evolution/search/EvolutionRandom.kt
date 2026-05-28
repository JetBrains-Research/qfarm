package org.jetbrains.bio.qfarm.evolution.search

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.params.BLUE
import org.jetbrains.bio.qfarm.GLOBAL_ENV
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.runEvolution
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.hp


fun topAttribute(
    prefixAttributes: List<Int>,
    searchAttributes: List<Int>,
    env: EvolutionEnvironment = GLOBAL_ENV
): Int? {
    val start = System.nanoTime()
    println("\n${BLUE}\uD83E\uDD47 SEARCHING FOR THE BEST ATTRIBUTE ... $RESET")
    require(searchAttributes.isNotEmpty()) { "searchAttributes must not be empty." }

    val parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        EvolutionContext.frontStack.lastOrNull()?.front ?: ISeq.empty()
    val hasParent = !parentFront.isEmpty

    val (popSize, maxGen) = hp.popSizeCheap to hp.maxGenCheap   // case: WITH parent

    val randomFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        runEvolution(prefixAttributes, searchAttributes, popSize, maxGen, parentFront, env)

    val result = if (!hasParent) {
        topAttributeNoParent(prefixAttributes, randomFront, env.datasetWithHeader)
    } else {
        topAttributeWithParent(prefixAttributes, randomFront, parentFront, env.datasetWithHeader)
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("Best avg: elapsed=%.2fs".format(elapsed))

    return result
}
