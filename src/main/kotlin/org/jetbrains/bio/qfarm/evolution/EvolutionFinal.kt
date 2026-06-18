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

    val totalStart = System.nanoTime()

    println("\n${PURPLE}$label : SEARCHING FOR THE BEST RANGE OF ${attributes.map { idx -> env.columnNames[idx]}} ... $RESET")
    require(attributes.isNotEmpty()) { "attributes must not be empty." }

    val treeStart = System.nanoTime()

    val oracle = CountingKdTreeOracle.fromDataset(
        dataset = env.datasetWithHeader,
        attributes = attributes,
        globalBounds = env.bounds,
        discreteInfo = env.discreteInfo,
        leafSize = 32
    )

    val treeMs = nsToMs(System.nanoTime() - treeStart)

    val evolutionStart = System.nanoTime()

    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        oracle.use {
            runEvolution(
                fixedAttributes = attributes,
                popSize = popSize,
                generationCount = generationCount,
                parentFront = parentFront,
                env = env,
                oracle = it
            )
        }

    val evolutionMs = nsToMs(System.nanoTime() - evolutionStart)

    println("$PURPLE [🏁 Pareto front (all) has ${front.size()} solutions] $RESET")

    if (front.isEmpty) {
        val totalMs = nsToMs(System.nanoTime() - totalStart)

        logRangeTimingRow(
            attributes = attributes,
            env = env,
            label = label,
            treeMs = treeMs,
            evolutionMs = evolutionMs,
            scoringMs = 0.0,
            totalMs = totalMs
        )

        println(
            """
            attributes      = ${attributes.map { env.columnNames[it] }}
            RANGE TIMING:
              treeBuild = ${fmtMs(treeMs)} (${fmtPct(treeMs, totalMs)})
              evolution = ${fmtMs(evolutionMs)} (${fmtPct(evolutionMs, totalMs)})
              scoring   = ${fmtMs(0.0)} (${fmtPct(0.0, totalMs)})
              total     = ${fmtMs(totalMs)}
            """.trimIndent()
        )

        println("$YELLOW [⚠️ No solutions matched the requested attributes. Returning empty result.] $RESET")
        return ScoredFront(front, doubleArrayOf())
    }

    val scoringStart = System.nanoTime()

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

    val scoringMs = nsToMs(System.nanoTime() - scoringStart)
    val totalMs = nsToMs(System.nanoTime() - totalStart)

    logRangeTimingRow(
        attributes = attributes,
        env = env,
        label = label,
        treeMs = treeMs,
        evolutionMs = evolutionMs,
        scoringMs = scoringMs,
        totalMs = totalMs
    )

    println(
        """
        attributes      = ${attributes.map { env.columnNames[it] }}
        RANGE TIMING:
          treeBuild = ${fmtMs(treeMs)} (${fmtPct(treeMs, totalMs)})
          evolution = ${fmtMs(evolutionMs)} (${fmtPct(evolutionMs, totalMs)})
          scoring   = ${fmtMs(scoringMs)} (${fmtPct(scoringMs, totalMs)})
          total     = ${fmtMs(totalMs)}
        """.trimIndent()
    )

    return ScoredFront(front, scores)
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

private fun nsToMs(ns: Long): Double =
    ns / 1_000_000.0

private fun fmtMs(ms: Double): String =
    "%.3f ms".format(ms)

private fun fmtPct(partMs: Double, totalMs: Double): String =
    if (totalMs == 0.0) "0.00%"
    else "%.2f%%".format(partMs * 100.0 / totalMs)

fun rangeSearchKindFromLabel(label: String): RangeSearchKind =
    when {
        label.contains("FULL", ignoreCase = true) ->
            RangeSearchKind.FULL_SEARCH

        label.contains("CHEAP", ignoreCase = true) ->
            RangeSearchKind.CHEAP_EVOLUTION

        else ->
            error("Unknown range search type for label: $label")
    }