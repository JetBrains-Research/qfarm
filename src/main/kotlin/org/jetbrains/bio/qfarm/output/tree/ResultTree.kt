package org.jetbrains.bio.qfarm.output.tree

import org.jetbrains.bio.qfarm.columnNames

fun toDOTFromTrie(
    root: RuleTreeNode = RULE_TREE_ROOT,
    header: List<String> = columnNames,
    title: String = ""
): String {

    val sb = StringBuilder()

    val cumulativeImprovement = cumulativeImprovement(root)

    fun esc(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")

    var nextId = 0
    fun newId(): String = "n${nextId++}"

    fun walk(node: RuleTreeNode, id: String = newId()): String {

        val label = esc(node.label ?: "")

        val tooltip = esc(
            buildTooltip(
                node,
                cumulativeImprovement,
                header
            )
        )

        val fill = nodeFillColor(
            node,
            cumulativeImprovement
        )

        val border =
            if (node.additionAttrIndex == null)
                "#4B8AE6"
            else
                "#cccccc"

        val urlAttr = node.plots?.combinedUrl?.let {
            """ , URL="${esc(relativeToTreeSvg(it))}", target="_blank" """
        } ?: ""

        sb.appendLine(
            """  $id [label="$label", tooltip="$tooltip", fillcolor="$fill", color="$border"$urlAttr];"""
        )

        for (child in node.children) {

            val childId = walk(child, newId())

            val name = child.additionAttrIndex?.let {
                header.getOrNull(it) ?: "attr#$it"
            }

            val p = pValue(child)

            val edgeLabel = buildString {
                if (name != null) append(name)
                if (p != null) append("\np=${"%.4f".format(p)}")
            }

            sb.appendLine(
                """  $id -> $childId [label="${esc(edgeLabel)}"];"""
            )
        }

        return id
    }

    sb.appendLine("digraph G {")
    sb.appendLine("""  label="${esc(title)}"; labelloc="t"; fontsize=18;""")
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

    walk(root)

    sb.appendLine("}")

    return sb.toString()
}
