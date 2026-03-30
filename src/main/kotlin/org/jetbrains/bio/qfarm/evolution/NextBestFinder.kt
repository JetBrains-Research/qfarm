package org.jetbrains.bio.qfarm.evolution

import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.MedianFront
import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.frontDistance
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.bio.qfarm.util.readLHS
import org.jetbrains.bio.qfarm.output.recordStep
import org.jetbrains.bio.qfarm.rightAttrIndex
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import kotlinx.coroutines.*


//fun nextBestFinder(
//    used: MutableSet<Int>,
//    prefix: List<Int>,
//    dataset: DatasetWithHeader = datasetWithHeader
//): Int? {
//    println("\n${CYAN}\uD83C\uDF1F FINDING THE NEXT BEST ADDITION TO THE PREFIX ${readLHS(prefix)} ... $RESET")
//
//    // Exclude already-used + right attribute from candidates
//    val right = rightAttrIndex
//    val searchAttributes: List<Int> =
//        (0 until dataset.header.size)
//            .asSequence()
//            .filter { it !in used && it != right }
//            .toList()
//
//    // If nothing left *except* the right attribute, stop
//    if (searchAttributes.isEmpty()) {
//        println("$RED ALL AVAILABLE ATTRIBUTES HAVE BEEN CONSIDERED! $RESET")
//        return null
//    }
//
//    val parentScoredFront = EvolutionContext.frontStack.lastOrNull()
//
//    val bestAttribute = topAttribute(prefix, searchAttributes)
//    if (bestAttribute == null) {
//        return null
//    }
//
//    val bestFront = topRange(prefix + listOf(bestAttribute))
//
//    // ========== DeLong statistical test ================
//
//    require(MedianFront.initialized) {
//        "MedianFront must be initialized before search"
//    }
//
//    val effectiveParent: ScoredFront =
//        if (parentScoredFront != null && !parentScoredFront.front.isEmpty) {
//            parentScoredFront
//        } else {
//            println("$YELLOW Using median baseline front $RESET")
//            MedianFront.scoredFront
//        }
//
//    val parentScores = effectiveParent.scores
//    val candidateScores = bestFront.scores
//    val labels = dataset.labels
//
//    val delongResult = DeLong.compare(
//        labels,
//        parentScores,
//        candidateScores
//    )
//
//    println("$CYAN DeLong z = ${"%.4f".format(delongResult.zScore)}" +
//            ", pOne = ${"%.6f".format(delongResult.pOneSided)} $RESET")
//
//    if (delongResult.pOneSided >= hp.alphaThreshold) {
//        println("$RED Addition rejected (not statistically significant at α=${hp.alphaThreshold}) $RESET")
//        return null
//    }
//
//    val improvement = frontDistance(
//        effectiveParent.front,
//        bestFront.front
//    )
//    println("$CYAN ΔFront area improvement = ${"%.4f".format(improvement)} $RESET")
//
//    EvolutionContext.frontStack.addLast(bestFront)
//
//    recordStep(
//        prefix = prefix,
//        addition = bestAttribute,
//        scoredFront = bestFront,
//        meta = mapOf(
//            "depth" to (prefix.size + 1),
//            "improvement" to improvement,
//            "pValue" to delongResult.pOneSided
//        )
//    )
//
//    return bestAttribute
//}

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
    used: Set<Int>,
    prefix: List<Int>,
    dataset: DatasetWithHeader = datasetWithHeader
): List<CandidateAddition> {

    println("\n${CYAN}🌿 Exploring ALL additions to ${readLHS(prefix)} ... $RESET")

    val right = rightAttrIndex

    val searchAttributes =
        (0 until dataset.header.size)
            .filter { it !in used && it != right }
            .filter { prefix.isEmpty() || it > prefix.last() }

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

                val candidateFront = topRange(prefix + attr)

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

    // keep ordering for stable traversal (best first)
    return results//.sortedByDescending { it.improvement }
}
