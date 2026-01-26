package org.jetbrains.bio.qfarm

var count = 0
var parent = 0

fun treeTraversal(prefix: List<Int>) {
    val locUsed = mutableSetOf<Int>()
    var maxLength = true

    val maxChildren = if (prefix.isEmpty()) hp.maxFirstChildren else hp.maxChildren
    var childCount = 0

    while (prefix.size < hp.maxDepth) {
        if (childCount >= maxChildren) {
            println("$YELLOW Reached child limit ($maxChildren) for this node. Stop branch.$RESET")
            break
        }
//        if (count == rightAttrIndex || count in prefix) {
//            count++
//        }
        val newRule = nextBestFinder(USED, prefix, datasetWithHeader, count)
        if (newRule == null) {
            println("$RED Nothing to add anymore!!!$RESET")
            parent++
            count = parent
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
    count++

    EvolutionContext.frontStack.removeLastOrNull()
    USED -= locUsed
}
