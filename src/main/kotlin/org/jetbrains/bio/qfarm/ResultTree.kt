package org.jetbrains.bio.qfarm

import java.io.File

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
        val raw = n.frontUrl ?: return null

        // Normalize browser-style file:// URLs
        val path = if (raw.startsWith("file://")) {
            raw.removePrefix("file://")
        } else {
            raw
        }

        val file = File(path)

        return when {
            file.isAbsolute -> file

            else -> File(PLOTS_DIR, path)
        }.takeIf { it.exists() }
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
    fun orangeFor(node: RuleTreeNode): String {
        if (node.additionAttrIndex == null) {
            // root: light blue at 50% opacity
            return "#EEF6FF80"
        }
        val t = improvementIntensity(node)

        // light -> strong orange
        val r0 = 0xFF; val g0 = 0xFB; val b0 = 0xF2   // almost white warm
        val r1 = 0xFF; val g1 = 0x8C; val b1 = 0x00   // strong orange

        fun lerp(a: Int, b: Int) = (a + (t * (b - a)).toInt()).coerceIn(0, 255)

        val r = lerp(r0, r1)
        val g = lerp(g0, g1)
        val b = lerp(b0, b1)

        // 80 = 50% alpha
        return String.format("#%02X%02X%02X80", r, g, b)
    }

    // ----------------------------------------------------

    fun walk(node: RuleTreeNode, id: String = newId()): String {
        val isRoot = node.additionAttrIndex == null
        val fill  = if (isRoot) "#EEF6FF80" else orangeFor(node)
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

