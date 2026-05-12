package org.jetbrains.bio.qfarm.output.tree

fun nodeImprovement(node: RuleTreeNode): Double {
    return node.steps.lastOrNull()
        ?.meta
        ?.get("improvement")
        ?.toString()
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
        ?: 0.0
}

fun cumulativeImprovement(root: RuleTreeNode): Map<RuleTreeNode, Double> {
    val result = mutableMapOf<RuleTreeNode, Double>()

    fun compute(node: RuleTreeNode, parentTotal: Double) {
        val total = parentTotal + nodeImprovement(node)
        result[node] = total
        node.children.forEach { compute(it, total) }
    }

    compute(root, 0.0)
    return result
}

fun pValue(node: RuleTreeNode): Double? {
    return node.steps.lastOrNull()
        ?.meta
        ?.get("pValue")
        ?.toString()
        ?.toDoubleOrNull()
}

fun isSignificant(node: RuleTreeNode): Boolean? {
    return pValue(node)?.let { it < 0.05 }
}
