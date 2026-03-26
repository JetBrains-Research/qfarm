package org.jetbrains.bio.qfarm.evolution

import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.TOPRULES
import org.jetbrains.bio.qfarm.USED
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.util.hp


fun treeTraversal(prefix: List<Int>) {
    val locUsed = mutableSetOf<Int>()
    var maxLength = true

    val maxChildren = if (prefix.isEmpty()) hp.maxFirstChildren else hp.maxChildren
    var childCount = 0

    while (prefix.size < hp.maxDepth) {
        if (childCount >= maxChildren) {
            println("$YELLOW Reached child limit ($maxChildren) for this node. Stop branch. $RESET")
            break
        }

        val newRule = nextBestFinder(USED, prefix, datasetWithHeader)
        if (newRule == null) {
            println("$RED Nothing to add anymore!!! $RESET")
            maxLength = false
            break
        }

        val currentRule = prefix + newRule
        TOPRULES += currentRule
        locUsed += newRule
        USED += newRule

        println("→ exploring child ${childCount + 1} of current prefix")

        // recurse
        treeTraversal(currentRule)

        childCount++
    }

    if (maxLength && childCount < maxChildren) {
        println("$YELLOW Max length of ${hp.maxDepth} reached or no more children $RESET")
    }

    EvolutionContext.frontStack.removeLastOrNull()
    USED -= locUsed
}
