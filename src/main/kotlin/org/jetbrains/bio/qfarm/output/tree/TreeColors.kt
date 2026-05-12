package org.jetbrains.bio.qfarm.output.tree

fun nodeFillColor(
    node: RuleTreeNode,
    cumulativeImprovement: Map<RuleTreeNode, Double>
): String {

    if (node.additionAttrIndex == null) {
        return "#EEF6FF80"
    }

    if (isSignificant(node) == false) {
        return "#FFCCCC80"
    }

    val values = cumulativeImprovement.values.filter { it > 0 }

    val min = values.minOrNull() ?: 0.0
    val max = values.maxOrNull() ?: 1.0

    val range = (max - min).takeIf { it > 0 } ?: 1.0

    val t = ((cumulativeImprovement[node] ?: 0.0) - min) / range

    fun lerp(a: Int, b: Int): Int =
        a + (t * (b - a)).toInt()

    val r = lerp(0xFF, 0xFF)
    val g = lerp(0xFB, 0x8C)
    val b = lerp(0xF2, 0x00)

    return String.format("#%02X%02X%02X80", r, g, b)
}
