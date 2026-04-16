package org.jetbrains.bio.qfarm.output.tree

import org.jetbrains.bio.qfarm.PLOTS_DIR
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.output.logs.RuleStep
import java.io.File
import java.net.URLDecoder

/**
 * Tree node = exactly ONE addition (attribute + range). Root has nulls.
 */
class RuleTreeNode(
    val additionAttrIndex: Int? = null,
    val depth: Int = 0,
    var parent: RuleTreeNode? = null,
    var frontUrl: String? = null,
    var label: String? = null
) {
    val steps: MutableList<RuleStep> = mutableListOf()
    val children: MutableList<RuleTreeNode> = mutableListOf()
}

/** Root of the tree */
val RULE_TREE_ROOT = RuleTreeNode()

/* ---------------------------- LABEL LOGIC ---------------------------- */

object NodeLabeler {

    private val barsCache = mutableMapOf<RuleTreeNode, Map<String, String>>()

    fun buildLabel(node: RuleTreeNode): String {
        if (node.additionAttrIndex == null) return "START"

        val bars = barsForNode(node)
        if (bars.isEmpty()) return ""

        return bars.entries.joinToString("\n") { (attr, bar) ->
            "${attr}:\n$bar"
        }
    }

    private fun barsForNode(n: RuleTreeNode): Map<String, String> {
        return barsCache.getOrPut(n) {
            val htmlFile = resolveFrontHtml(n) ?: return@getOrPut emptyMap()

            try {
                buildBarsFromHtml(htmlFile)
            } catch (e: Exception) {
                println("Failed to parse bars from $htmlFile: $e")
                emptyMap()
            }
        }
    }

    private fun resolveFrontHtml(n: RuleTreeNode): File? {
        val raw = n.frontUrl ?: return null

        val normalized = if (raw.startsWith("file://")) {
            raw.removePrefix("file://")
        } else raw

        val decoded = URLDecoder.decode(normalized, "UTF-8")
        val file = File(decoded)

        val resolved = if (file.isAbsolute) file else File(PLOTS_DIR, decoded)

        return resolved.takeIf { it.exists() }
    }

//    private fun abbrevAttr(name: String, maxLen: Int = 20): String {
//        return if (name.length <= maxLen) name else name.take(maxLen - 1) + "."
//    }
}

/* ---------------------------- DOT Visualization ------------------------- */

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

    fun isSignificant(n: RuleTreeNode): Boolean? {
        val pValue = n.steps.lastOrNull()
            ?.meta?.get("pValue")
            ?.toString()?.toDoubleOrNull()

        return pValue?.let { it < 0.05 }
    }

    // Collect nodes
    val allNodes = mutableListOf<RuleTreeNode>()
    fun collect(n: RuleTreeNode) {
        allNodes += n
        n.children.forEach(::collect)
    }
    collect(root)

    val nodeImprovement = allNodes.associateWith { n ->
        n.steps.lastOrNull()
            ?.meta?.get("improvement")
            ?.toString()?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: 0.0
    }

    val cumulativeImprovement = mutableMapOf<RuleTreeNode, Double>()
    fun compute(n: RuleTreeNode, parent: Double) {
        val total = parent + (nodeImprovement[n] ?: 0.0)
        cumulativeImprovement[n] = total
        n.children.forEach { compute(it, total) }
    }
    compute(root, 0.0)

    fun tooltip(n: RuleTreeNode): String {
        val name = n.additionAttrIndex?.let {
            header.getOrNull(it) ?: "attr#$it"
        } ?: "START"

        val delta = n.steps.lastOrNull()
            ?.meta?.get("improvement")
            ?.toString()?.toDoubleOrNull()

        val total = cumulativeImprovement[n]

        val pValue = n.steps.lastOrNull()
            ?.meta?.get("pValue")
            ?.toString()?.toDoubleOrNull()

        return buildString {
            append("Addition: $name")

            if (delta != null) {
                append("\nΔ area = ${"%.4f".format(delta)}")
            }

            if (total != null && total > 0.0) {
                append("\nTotal area = ${"%.4f".format(total)}")
            }

            if (pValue != null) {
                append("\np-value = ${"%.6f".format(pValue)}")
                append(if (pValue < 0.05) " (✓ significant)" else " (✗ ns)")
            }
        }
    }

    fun fillColor(node: RuleTreeNode): String {
        if (node.additionAttrIndex == null) return "#EEF6FF80"

        if (isSignificant(node) == false) return "#FFCCCC80"

        val values = cumulativeImprovement.values.filter { it > 0 }
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 1.0
        val range = (max - min).takeIf { it > 0 } ?: 1.0

        val t = ((cumulativeImprovement[node] ?: 0.0) - min) / range

        fun lerp(a: Int, b: Int) = (a + (t * (b - a)).toInt())

        val r = lerp(0xFF, 0xFF)
        val g = lerp(0xFB, 0x8C)
        val b = lerp(0xF2, 0x00)

        return String.format("#%02X%02X%02X80", r, g, b)
    }

    fun walk(node: RuleTreeNode, id: String = newId()): String {

        val label = esc(node.label ?: "")
        val tip = esc(tooltip(node))
        val fill = fillColor(node)
        val color = if (node.additionAttrIndex == null) "#4B8AE6" else "#cccccc"

        val urlAttr = node.frontUrl?.let {
            """ , URL="${esc(it)}", target="_blank" """
        } ?: ""

        sb.appendLine(
            """  $id [label="$label", tooltip="$tip", fillcolor="$fill", color="$color"$urlAttr];"""
        )

        for (child in node.children) {
            val cid = newId()
            val childId = walk(child, cid)

            val name = child.additionAttrIndex?.let {
                header.getOrNull(it) ?: "attr#$it"
            }

            val p = child.steps.lastOrNull()
                ?.meta?.get("pValue")
                ?.toString()?.toDoubleOrNull()

            val edgeLabel = buildString {
                if (name != null) append(name)
                if (p != null) append("\np=${"%.4f".format(p)}")
            }

            sb.appendLine("""  $id -> $childId [label="${esc(edgeLabel)}"];""")
        }

        return id
    }

    walk(root)
    sb.appendLine("}")
    return sb.toString()
}
