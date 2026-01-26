package org.jetbrains.bio.qfarm

//fun nextBestFinder(
//    used: MutableSet<Int>,
//    prefix: List<Int>,
//    dataset: DatasetWithHeader = datasetWithHeader,
//    count: Int
//): Int? {
//    println("\n$CYAN\uD83C\uDF1F FINDING THE NEXT BEST ADDITION TO THE PREFIX ${readLHS(prefix)} ... $RESET")
//
//    // Exclude already-used + right attribute from candidates
//    val right = rightAttrIndex
////    val searchAttributes: List<Int> =
////        (0 until dataset.header.size)
////            .asSequence()
////            .filter { it !in used && it != right }
////            .toList()
//
//    // If nothing left *except* the right attribute, stop
////    if (searchAttributes.isEmpty()) {
////        println("$RED ALL AVAILABLE ATTRIBUTES HAVE BEEN CONSIDERED! $RESET")
////        return null
////    }
//
//    val parentFront = null
////
////    val bestAttribute = topAttribute(prefix, searchAttributes)
////    if (bestAttribute == null) {
////        return null
////    }
//
//    if (count >= min(11_000, columnNames.size)) {
//        return null
//    }
//
//    val bestAttribute = count
//
//    val best = topRange(prefix + listOf(bestAttribute))
//    val bestRule = best.ranges
//    val bestFront = best.front
//
//    val improvement = frontDistance(parentFront, bestFront)
//    println("$CYAN ΔFront area improvement = ${"%.4f".format(improvement)}$RESET")
//
////    if (parentFront != null && !parentFront.isEmpty) {
////        if (improvement < hp.improvementThreshold) {
////            println("$RED Addition rejected (below threshold: ${hp.improvementThreshold})$RESET")
////            return null
////        }
////    }
//
////    EvolutionContext.frontStack.addLast(bestFront)
//
//    recordStep(
//        prefix = prefix,
//        addition = bestAttribute,
//        front = bestFront,
//        meta = mapOf(
//            "depth" to (prefix.size + 1),
//            "improvement" to improvement
//        )
//    )
//
//    return bestAttribute
//}


fun nextBestFinder(
    used: MutableSet<Int>,
    prefix: List<Int>,
    dataset: DatasetWithHeader = datasetWithHeader,
    count: Int
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

    val best = topRange(prefix + listOf(bestAttribute))
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

    recordStep(
        prefix = prefix,
        addition = bestAttribute,
        front = bestFront,
        meta = mapOf(
            "depth" to (prefix.size + 1),
            "improvement" to improvement
        )
    )

    return bestAttribute
}
