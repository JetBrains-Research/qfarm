package org.jetbrains.bio.qfarm.output.tree

fun buildTooltip(
    node: RuleTreeNode,
    cumulativeImprovement: Map<RuleTreeNode, Double>,
    header: List<String>
): String {

    val name = node.additionAttrIndex?.let {
        header.getOrNull(it) ?: "attr#$it"
    } ?: "START"

    val delta = node.steps.lastOrNull()
        ?.meta?.get("improvement")
        ?.toString()?.toDoubleOrNull()

    val total = cumulativeImprovement[node]

    val p = pValue(node)

    return buildString {
        append("Addition: $name")

        if (delta != null) {
            append("\nΔ area = ${"%.4f".format(delta)}")
        }

        if (total != null && total > 0.0) {
            append("\nTotal area = ${"%.4f".format(total)}")
        }

        if (p != null) {
            append("\np-value = ${"%.6f".format(p)}")
            append(if (p < 0.05) " (✓ significant)" else " (✗ ns)")
        }
    }
}
