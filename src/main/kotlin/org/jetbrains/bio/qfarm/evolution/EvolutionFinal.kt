package org.jetbrains.bio.qfarm.evolution

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.GLOBAL_ENV
import org.jetbrains.bio.qfarm.evaluation.CountingKdTreeOracle
import org.jetbrains.bio.qfarm.params.PURPLE
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.YELLOW
import org.jetbrains.bio.qfarm.evaluation.computeFrontScores
import org.jetbrains.bio.qfarm.params.RocComparisonMode
import org.jetbrains.bio.qfarm.params.hp

fun topRange(
    attributes: List<Int>,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    env: EvolutionEnvironment = GLOBAL_ENV,
    popSize: Int = hp.popSizeFull,
    generationCount: Int = hp.maxGenFull,
    label: String = "🏆"
): ScoredFront {

    val start = System.nanoTime()

    println("\n${PURPLE}$label : SEARCHING FOR THE BEST RANGE OF ${attributes.map { idx -> env.columnNames[idx]}} ... $RESET")
    require(attributes.isNotEmpty()) { "attributes must not be empty." }

    CountingKdTreeOracle
        .fromDataset(
            dataset = env.datasetWithHeader,
            attributes = attributes,
            globalBounds = env.bounds,
            leafSize = 32
        )
        .use { oracle ->

            val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
                runEvolution(
                    fixedAttributes = attributes,
                    popSize = popSize,
                    generationCount = generationCount,
                    parentFront = parentFront,
                    env = env,
                    oracle = oracle
                )

            println("$PURPLE [🏁 Pareto front (all) has ${front.size()} solutions] $RESET")

            if (front.isEmpty) {
                println("$YELLOW [⚠️ No solutions matched the requested attributes. Returning empty result.] $RESET")
                return ScoredFront(front, doubleArrayOf())
            }

            val rocFront = when (hp.rocComparison) {

                RocComparisonMode.CHILD -> {
                    front
                }

                RocComparisonMode.CHILD_PLUS_PARENT -> {

                    if (parentFront == null || parentFront.isEmpty) {
                        front
                    } else {
                        parentFront.append(front)
                    }
                }
            }

            val scores = computeFrontScores(rocFront, env)

            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            println("Range finder: elapsed=%.2fs".format(elapsed))

            return ScoredFront(front, scores)
        }
}

fun cheapTopRange(
    attributes: List<Int>,
    env: EvolutionEnvironment = GLOBAL_ENV,
): ScoredFront {
    val parentFront = EvolutionContext.frontStack.lastOrNull()?.front

    return topRange(
        attributes = attributes,
        parentFront = parentFront,
        env = env,
        popSize = hp.popSizeCheap,
        generationCount = hp.maxGenCheap,
        label = "⚡ CHEAP RANGE SEARCH"
    )
}

fun fullTopRange(
    attributes: List<Int>,
    env: EvolutionEnvironment = GLOBAL_ENV,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? = null
): ScoredFront {

    val effectiveParent = when {
        parentFront != null -> parentFront
        else                -> EvolutionContext.frontStack.lastOrNull()?.front
    }

    return topRange(
        attributes = attributes,
        parentFront = effectiveParent,
        env = env,
        popSize = hp.popSizeFull,
        generationCount = hp.maxGenFull,
        label = "🏆 FULL SEARCH"
    )
}
