package org.jetbrains.bio.qfarm.evolution.search

import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.params.RED
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.fronts.frontDistance
import org.jetbrains.bio.qfarm.util.readLHS
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import kotlinx.coroutines.*
import org.jetbrains.bio.qfarm.evaluation.random.RandomAucBaseline
import org.jetbrains.bio.qfarm.evaluation.random.bonferroniCorrect
import org.jetbrains.bio.qfarm.evaluation.random.empiricalAucPValueGreater
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult

data class CandidateAddition(
    val attr: Int,
    val front: ScoredFront,
    val improvement: Double,

    // depth > 1
    val deLong: DeLongResult? = null,

    // level 1
    val auc: Double? = null,
    val randomAucP: Double? = null,
    val randomAucAdjustedP: Double? = null,
    val randomAucPass: Boolean? = null
)

fun evaluateAllAdditions(
    prefix: List<Int>,
    searchAttributes: List<Int>,
    dataset: DatasetWithHeader = datasetWithHeader
): List<CandidateAddition> {

    val label = if (prefix.isEmpty()) "empty rule" else readLHS(prefix)

    println("\n${CYAN}🌿 Exploring ALL additions to $label ... $RESET")

    if (searchAttributes.isEmpty()) {
        println("$RED No attributes left to explore $RESET")
        return emptyList()
    }

    val isLevel1 = prefix.isEmpty()
    val nTests = searchAttributes.size

    if (isLevel1) {
        RandomAucBaseline.requireReady()
    }

    val parentScoredFront = EvolutionContext.frontStack.lastOrNull()

    val effectiveParent: ScoredFront? =
        if (!isLevel1 && parentScoredFront != null && !parentScoredFront.front.isEmpty) {
            parentScoredFront
        } else {
            null
        }

    val capturedParentFront = effectiveParent?.front

    val labels = dataset.labels

    val results = runBlocking {

        searchAttributes.map { attr ->

            async(Dispatchers.Default) {

                val candidateFront = fullTopRange(
                        attributes = prefix + attr,
                        parentFront = capturedParentFront
                    )

                if (isLevel1) {

                    val auc = AUC.compute(
                        labels,
                        candidateFront.scores
                    )

                    val rawP = empiricalAucPValueGreater(
                        observedAuc = auc,
                        randomAucs = RandomAucBaseline.aucs
                    )

                    val adjustedP = bonferroniCorrect(
                        rawP = rawP,
                        nTests = nTests
                    )

                    CandidateAddition(
                        attr = attr,
                        front = candidateFront,
                        improvement = 0.0,
                        deLong = null,
                        auc = auc,
                        randomAucP = rawP,
                        randomAucAdjustedP = adjustedP,
                        randomAucPass = adjustedP < hp.alphaThreshold
                    )

                } else {

                    require(effectiveParent != null) {
                        "Non-level-1 candidate requires a parent front."
                    }

                    val delong = DeLong.compare(
                        labels,
                        effectiveParent.scores,
                        candidateFront.scores
                    )

                    val improvement = frontDistance(
                        effectiveParent.front,
                        candidateFront.front
                    )

                    CandidateAddition(
                        attr = attr,
                        front = candidateFront,
                        improvement = improvement,
                        deLong = delong,
                        auc = delong.auc2
                    )
                }
            }

        }.awaitAll()
    }

    // keep ordering for stable traversal
    return results.sortedByDescending {
        it.auc ?: Double.NEGATIVE_INFINITY
    }
}
