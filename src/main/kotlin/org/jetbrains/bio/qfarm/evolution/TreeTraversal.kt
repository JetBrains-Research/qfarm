package org.jetbrains.bio.qfarm.evolution

import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.TOPRULES
import org.jetbrains.bio.qfarm.USED
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.output.recordStep
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.bio.qfarm.util.readLHS


fun treeTraversal(prefix: List<Int>) {

    val locUsed = mutableSetOf<Int>()
    var maxLength = true

    val maxChildren = if (prefix.isEmpty()) hp.maxFirstChildren else hp.maxChildren

    while (prefix.size < hp.maxDepth) {

        val candidates = evaluateAllAdditions(USED, prefix, datasetWithHeader)

        if (candidates.isEmpty()) {
            println("$RED Nothing to add anymore!!! $RESET")
            maxLength = false
            break
        }

        var childCount = 0

        for ((i, candidate) in candidates.withIndex()) {

            if (childCount >= maxChildren) {
                println("$YELLOW Reached child limit ($maxChildren) for this node. $RESET")
                break
            }

            val attr = candidate.attr
            val currentRule = prefix + attr

            println("→ exploring child ${childCount + 1}: ${readLHS(currentRule)}")

            // push front
            EvolutionContext.frontStack.addLast(candidate.front)

            recordStep(
                prefix = prefix,
                addition = attr,
                scoredFront = candidate.front,
                meta = mapOf(
                    "depth" to (prefix.size + 1),
                    "improvement" to candidate.improvement,

                    "pValue" to candidate.pValue,
                    "pValueTwoSided" to candidate.pValueTwoSided,
                    "zScore" to candidate.zScore,
                    "aucParent" to candidate.aucParent,
                    "aucChild" to candidate.aucChild,
                    "varianceParent" to candidate.varianceParent,
                    "varianceChild" to candidate.varianceChild,
                    "covariance" to candidate.covariance
                )
            )

            USED += attr
            locUsed += attr
            TOPRULES += currentRule

            // recurse
            treeTraversal(currentRule)

            // cleanup after recursion
            EvolutionContext.frontStack.removeLastOrNull()
            USED -= attr

            childCount++
        }

        break // IMPORTANT: prevent infinite while loop
    }

    if (maxLength) {
        println("$YELLOW Max depth or branch complete for ${readLHS(prefix)} $RESET")
    }

    USED -= locUsed
}
