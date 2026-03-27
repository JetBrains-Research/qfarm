package org.jetbrains.bio.qfarm.output

import org.jetbrains.bio.qfarm.PLOTS_DIR
import org.jetbrains.bio.qfarm.columnNames
import java.io.File
import java.net.URLDecoder
import kotlin.collections.iterator

/**
 * Tree node = exactly ONE addition (attribute + range). Root has nulls.
 * Identity uses (additionAttrIndex, additionRange), so different ranges become different nodes.
 * Labeling stays clean (attribute name only).
 */
class RuleTreeNode(
    val additionAttrIndex: Int? = null,                            // null for root
    val depth: Int = 0,
    var frontUrl: String? = null
) {
    /** All steps recorded at THIS node (usually 1, but we allow re-runs). */
    val steps: MutableList<RuleStep> = mutableListOf()
    /** Children in insertion order (DFS order). */
    val children: MutableList<RuleTreeNode> = mutableListOf()
}

/** Root of the tree (empty path). */
val RULE_TREE_ROOT = RuleTreeNode()


/* ---------------------------- DOT Visualization ------------------------- */
/**
 * Export the rule tree to Graphviz DOT.
 * Each node = one addition (attribute label; tooltip shows range).
 * Edges connect prefix → addition, preserving DFS insertion order.
 */
fun toDOTFromTrie(
    root: RuleTreeNode = RULE_TREE_ROOT,
    header: List<String> = columnNames,
    title: String = ""
): String {
    val sb = StringBuilder()
    sb.appendLine("digraph G {")
    sb.appendLine("""  label="$title"; labelloc="t"; fontsize=18;""")
    sb.appendLine("""  rankdir=LR; splines=true; overlap=false;""")
    sb.appendLine(
        """  node [
           shape=box,
           style="rounded,filled",
           fillcolor="#f9f9f9",
           color="#cccccc",
           fontsize=11,
           fontname="Courier"
       ];"""
    )
    sb.appendLine("""  edge [color="#999999", arrowsize=0.6];""")

    var nextId = 0
    fun newId() = "n${nextId++}"
    fun esc(s: String) = s.replace("\"", "\\\"")

    val barsCache = mutableMapOf<RuleTreeNode, Map<String, String>>()
    fun resolveFrontHtml(n: RuleTreeNode): File? {
        val raw = n.frontUrl ?: run {
            println("❌ frontUrl is NULL")
            return null
        }

        val normalized = if (raw.startsWith("file://")) {
            raw.removePrefix("file://")
        } else raw

        val decoded = URLDecoder.decode(normalized, "UTF-8")
        val file = File(decoded)

        val resolved = when {
            file.isAbsolute -> file
            else -> File(PLOTS_DIR, decoded)
        }

        return resolved.takeIf { it.exists() }
    }

    fun barsForNode(n: RuleTreeNode): Map<String, String> {
        return barsCache.getOrPut(n) {
            val htmlFile = resolveFrontHtml(n)
                ?: return@getOrPut emptyMap()

            try {
                buildBarsFromHtml(htmlFile)
            } catch (e: Exception) {
                println("Failed to parse bars from ${htmlFile}: $e")
                emptyMap()
            }
        }
    }

    fun abbrevAttr(name: String, maxLen: Int = 20): String {
        return if (name.length <= maxLen)
            name
        else
            name.take(maxLen - 1) + "."
    }

    fun isSignificant(n: RuleTreeNode): Boolean? {
        val pValue = n.steps.lastOrNull()
            ?.meta
            ?.get("pValue")
            ?.toString()
            ?.toDoubleOrNull()

        return pValue?.let { it < 0.05 }
    }

    fun nodeLabel(n: RuleTreeNode): String {
        // Root stays minimal
        if (n.additionAttrIndex == null) return "START"

        val bars = barsForNode(n)
        if (bars.isEmpty()) {
            return ""
        }

        // Stable order (alphabetical by attribute name)
        val sortedBars = bars.toSortedMap()

        return buildString {
            for ((attr, bar) in sortedBars) {
                val shortName = abbrevAttr(attr, 20)
                append(shortName)
                append(":\n")
                append(bar)
                append("\n")
            }
        }.trimEnd()
    }

    // Collect all nodes & raw improvements
    val allNodes = mutableListOf<RuleTreeNode>()
    fun collectNodes(n: RuleTreeNode) {
        allNodes += n
        n.children.forEach(::collectNodes)
    }
    collectNodes(root)

    val nodeImprovement: Map<RuleTreeNode, Double> = allNodes.associateWith { n ->
        n.steps.lastOrNull()
            ?.meta
            ?.get("improvement")
            ?.toString()
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: 0.0
    }

    // compute cumulative improvement = sum(parent + self) along each path
    val cumulativeImprovement = mutableMapOf<RuleTreeNode, Double>()
    fun computeCumulative(n: RuleTreeNode, parentCum: Double) {
        val own = nodeImprovement[n] ?: 0.0
        val cum = parentCum + own
        cumulativeImprovement[n] = cum
        n.children.forEach { child ->
            computeCumulative(child, cum)
        }
    }
    computeCumulative(root, 0.0)

    fun tooltip(n: RuleTreeNode): String {
        val name =
            n.additionAttrIndex?.let { idx ->
                header.getOrNull(idx) ?: "attr#$idx"
            } ?: "START"

        val delta = n.steps.lastOrNull()
            ?.meta
            ?.get("improvement")
            ?.toString()
            ?.toDoubleOrNull()

        val total = cumulativeImprovement[n]

        val pValue = n.steps.lastOrNull()
            ?.meta
            ?.get("pValue")
            ?.toString()
            ?.toDoubleOrNull()

        return buildString {
            append("Addition: $name")

            if (delta != null) {
                append("\nΔ area = ")
                append(String.format("%.4f", delta))
            }

            if (total != null && total > 0.0) {
                append("\nTotal area = ")
                append(String.format("%.4f", total))
            }

            val alpha = 0.05

            if (pValue != null) {
                append("\np-value = ")
                append(String.format("%.6f", pValue))

                if (pValue < alpha) {
                    append("  (✓ significant)")
                } else {
                    append("  (✗ ns)")
                }
            }
        }
    }

    // Linear normalization based on actual cumulative improvement
    val positiveValues = cumulativeImprovement.values.filter { it > 0.0 }
    val minImp = positiveValues.minOrNull() ?: 0.0
    val maxImp = positiveValues.maxOrNull() ?: 0.0
    val impRange = (maxImp - minImp).takeIf { it > 0.0 } ?: 1.0

    fun improvementIntensity(n: RuleTreeNode): Double {
        val v = cumulativeImprovement[n] ?: 0.0
        if (v <= 0.0) return 0.0
        return ((v - minImp) / impRange).coerceIn(0.0, 1.0)
    }


    // Interpolate between *very pale* and *strong* orange, with 50% opacity
    fun fillColorFor(node: RuleTreeNode): String {

        // Root stays special
        if (node.additionAttrIndex == null) {
            return "#EEF6FF80"
        }

        val significant = isSignificant(node)

        // ❗ Not significant → light red
        if (significant == false) {
            return "#FFCCCC80"   // soft red with transparency
        }

        // Significant or unknown → use orange gradient
        val t = improvementIntensity(node)

        val r0 = 0xFF; val g0 = 0xFB; val b0 = 0xF2
        val r1 = 0xFF; val g1 = 0x8C; val b1 = 0x00

        fun lerp(a: Int, b: Int) = (a + (t * (b - a)).toInt()).coerceIn(0, 255)

        val r = lerp(r0, r1)
        val g = lerp(g0, g1)
        val b = lerp(b0, b1)

        return String.format("#%02X%02X%02X80", r, g, b)
    }

    // ----------------------------------------------------

    fun walk(node: RuleTreeNode, id: String = newId()): String {
        val isRoot = node.additionAttrIndex == null
        val fill = fillColorFor(node)
        val color = if (isRoot) "#4B8AE6" else "#cccccc"
        val tip = esc(tooltip(node))

        val url = node.frontUrl
            ?.let(::esc)

        val urlAttr = if (url != null) """ , URL="$url", target="_blank" """ else ""
        val label = esc(nodeLabel(node))

        sb.appendLine(
            """  $id [label="$label", tooltip="$tip", fillcolor="$fill", color="$color"$urlAttr];"""
        )

        for (child in node.children) {
            val cid = newId()
            val childId = walk(child, cid)
            sb.appendLine("  $id -> $childId;")
        }
        return id
    }

    walk(root)
    sb.appendLine("}")
    return sb.toString()
}
