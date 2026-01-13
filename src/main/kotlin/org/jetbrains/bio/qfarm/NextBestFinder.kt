package org.jetbrains.bio.qfarm

fun nextBestFinder(
    used: MutableSet<Int>,
    prefix: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    dataset: DatasetWithHeader = datasetWithHeader
): Pair<Map<Int, ClosedFloatingPointRange<Double>>, Int>? {
    println("\n$CYAN\uD83C\uDF1F FINDING THE NEXT BEST ADDITION TO THE PREFIX ${readLHS(prefix, false)} ... $RESET")

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

    val lowerBest = bounds[bestAttribute][0]
    val upperBest = bounds[bestAttribute][1]
    val best = topRange(prefix + listOf(bestAttribute to (lowerBest..upperBest)))
    val bestRule = best.ranges
    val bestFront = best.front

    val improvement = frontDistance(parentFront, bestFront)
    println("$CYAN ΔFront area improvement = ${"%.4f".format(improvement)}$RESET")

    if (parentFront != null && !parentFront.isEmpty) {
        if (improvement < hp.improvementThreshold) {
            println("$RED Addition rejected (below threshold: ${hp.improvementThreshold})$RESET")
            return null
        }
    }

    EvolutionContext.frontStack.addLast(bestFront)

    val url = renderFrontPlotUrl(
        parentFront,
        bestFront,
        title = "Front shift: ${readLHS(bestRule.toList(), onlyNames = true)} \nwith addition ${columnNames[bestAttribute]} \nImprovement area = ${"%.3f".format(improvement)}",
        randomFront = false
    )
    val additionNode = recordStep(
        prefixRanges = prefix,
        addition = bestAttribute to bestRule[bestAttribute]!!,
        front = bestFront,
        meta = mapOf(
            "depth" to (prefix.size + 1),
            "frontUrl" to (url ?: ""),
            "improvement" to improvement
        )
    )

    // Ensure node stores the URL
    if (url != null) additionNode.frontUrl = url

    return bestRule to bestAttribute
}
