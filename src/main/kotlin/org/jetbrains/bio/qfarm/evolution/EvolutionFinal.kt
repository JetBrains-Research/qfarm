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
import org.jetbrains.bio.qfarm.evaluation.fronts.computeFrontScores
import org.jetbrains.bio.qfarm.params.RocComparisonMode
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.paretoFrontOf

fun topRange(
    attributes: List<Int>,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    phase: EvolutionPhase,
    env: EvolutionEnvironment = GLOBAL_ENV,
    popSize: Int = hp.popSizeFull,
    generationCount: Int = hp.maxGenFull,
    label: String = "🏆"
): ScoredFront {

    println("\n${PURPLE}$label : SEARCHING FOR THE BEST RANGE OF ${attributes.map { idx -> env.columnNames[idx]}} ... $RESET")
    require(attributes.isNotEmpty()) { "attributes must not be empty." }

    val evolutionSeed = seedForEvolution(
        rootSeed = hp.seed,
        attributes = attributes,
        phase = phase
    )

    val oracle = CountingKdTreeOracle.fromDataset(
        dataset = env.datasetWithHeader,
        attributes = attributes,
        globalBounds = env.bounds,
        discreteInfo = env.discreteInfo,
        leafSize = 32
    )

    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        oracle.use {
            runEvolution(
                fixedAttributes = attributes,
                popSize = popSize,
                generationCount = generationCount,
                parentFront = parentFront,
                env = env,
                oracle = it,
                evolutionSeed = evolutionSeed
            )
        }

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

        RocComparisonMode.MERGE -> {
            if (parentFront == null || parentFront.isEmpty) {
                front
            } else {
                ISeq.of(paretoFrontOf(parentFront.append(front)))
            }
        }
    }

    val scores = computeFrontScores(rocFront, env)

    return ScoredFront(front, scores)
}

fun cheapTopRange(
    attributes: List<Int>,
    env: EvolutionEnvironment = GLOBAL_ENV,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? =
        EvolutionContext.frontStack.lastOrNull()?.front
): ScoredFront {

    return topRange(
        attributes = attributes,
        parentFront = parentFront,
        phase = EvolutionPhase.CHEAP,
        env = env,
        popSize = hp.popSizeCheap,
        generationCount = hp.maxGenCheap,
        label = "⚡ CHEAP RANGE SEARCH"
    )
}

fun fullTopRange(
    attributes: List<Int>,
    env: EvolutionEnvironment = GLOBAL_ENV,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? =
        EvolutionContext.frontStack.lastOrNull()?.front
): ScoredFront {

    return topRange(
        attributes = attributes,
        parentFront = parentFront,
        phase = EvolutionPhase.FULL,
        env = env,
        popSize = hp.popSizeFull,
        generationCount = hp.maxGenFull,
        label = "🏆 FULL SEARCH"
    )
}
