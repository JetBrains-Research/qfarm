package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.statistics.delong.DeLong


fun nextBestFinder(
    used: MutableSet<Int>,
    prefix: List<Int>,
    dataset: DatasetWithHeader = datasetWithHeader
): Int? {
    println("\n$CYAN\uD83C\uDF1F FINDING THE NEXT BEST ADDITION TO THE PREFIX ${readLHS(prefix)} ... $RESET")

    // Exclude already-used + right attribute from candidates
    val right = rightAttrIndex
    val searchAttributes: List<Int> =
        (0 until dataset.header.size)
            .asSequence()
            .filter { it !in used && it != right }
            .toList()

    // If nothing left *except* the right attribute, stop
    if (searchAttributes.isEmpty()) {
        println("$RED ALL AVAILABLE ATTRIBUTES HAVE BEEN CONSIDERED! $RESET")
        return null
    }

    val parentFront = EvolutionContext.frontStack.lastOrNull()

    val bestAttribute = topAttribute(prefix, searchAttributes)
    if (bestAttribute == null) {
        return null
    }

    val bestFront = topRange(prefix + listOf(bestAttribute))

    // ==============================
    // DeLong statistical test
    // ==============================

    if (parentFront != null && !parentFront.front.isEmpty) {

        // TODO: change structure of Front to include scores...
        val parentScores = parentFront.scores
        val candidateScores = bestFront.scores
        val labels = dataset.labels

        val delongResult = DeLong.compare(
            labels,
            parentScores,
            candidateScores
        )

        println("$CYAN DeLong z = ${"%.4f".format(delongResult.zScore)}" +
                ", pOne = ${"%.6f".format(delongResult.pOneSided)}$RESET")

        if (delongResult.pOneSided >= hp.alphaThreshold) {
            println("$RED Addition rejected (not statistically significant at α=${hp.alphaThreshold})$RESET")
            return null
        }
    }

    val improvement = frontDistance(parentFront?.front, bestFront.front)
    println("$CYAN ΔFront area improvement = ${"%.4f".format(improvement)}$RESET")

    EvolutionContext.frontStack.addLast(bestFront)

    recordStep(
        prefix = prefix,
        addition = bestAttribute,
        front = bestFront.front,
        meta = mapOf(
            "depth" to (prefix.size + 1),
            "improvement" to improvement
        )
    )

    return bestAttribute
}
