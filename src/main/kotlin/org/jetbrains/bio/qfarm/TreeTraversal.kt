package org.jetbrains.bio.qfarm

fun treeTraversal(prefix: List<Pair<Int, ClosedFloatingPointRange<Double>>>) {
    val locUsed = mutableSetOf<Int>()
    var maxLength = true

    val maxChildren = if (prefix.isEmpty()) hp.maxFirstChildren else hp.maxChildren
    var childCount = 0

    while (prefix.size < hp.maxDepth) {
        if (childCount >= maxChildren) {
            println("$YELLOW Reached child limit ($maxChildren) for this node. Stop branch.$RESET")
            break
        }

        val newRule = nextBestFinder(USED, prefix)
        if (newRule == null) {
            println("$RED Nothing to add anymore!!!$RESET")
            maxLength = false
            break
        }

        val currentRule = newRule.first.toList()
        TOPRULES += currentRule
        locUsed += newRule.second
        USED += newRule.second

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
