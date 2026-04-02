package org.jetbrains.bio.qfarm.evolution

import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.MedianFront
import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.frontDistance
import org.jetbrains.bio.qfarm.util.readLHS
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import kotlinx.coroutines.*

data class CandidateAddition(
    val attr: Int,
    val front: ScoredFront,
    val improvement: Double,

    // stats
    val pValue: Double,
    val pValueTwoSided: Double,
    val zScore: Double,
    val aucParent: Double,
    val aucChild: Double,
    val varianceParent: Double,
    val varianceChild: Double,
    val covariance: Double
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

    val parentScoredFront = EvolutionContext.frontStack.lastOrNull()

    val effectiveParent: ScoredFront =
        if (parentScoredFront != null && !parentScoredFront.front.isEmpty) {
            parentScoredFront
        } else {
            println("$YELLOW Using median baseline front $RESET")
            MedianFront.scoredFront
        }

    val parentScores = effectiveParent.scores
    val labels = dataset.labels

    val results = runBlocking {

        searchAttributes.map { attr ->

            async(Dispatchers.Default) {

                val candidateFront = fullTopRange(prefix + attr)

                val delong = DeLong.compare(
                    labels,
                    parentScores,
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

                    pValue = delong.pOneSided,
                    pValueTwoSided = delong.pTwoSided,
                    zScore = delong.zScore,
                    aucParent = delong.auc1,
                    aucChild = delong.auc2,
                    varianceParent = delong.variance1,
                    varianceChild = delong.variance2,
                    covariance = delong.covariance
                )
            }

        }.awaitAll()
    }

    // keep ordering for stable traversal
    return results.sortedByDescending { it.aucChild }
}
