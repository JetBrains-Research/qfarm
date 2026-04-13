package org.jetbrains.bio.qfarm.output.fronts

import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import java.io.BufferedWriter
import java.io.File

val SEPARATOR = "_".repeat(60)

// VERSION LIGHT: all in one row, no rule, only plots
fun writeTxtLight(rows: List<ExportRuleRow>, file: File) {

    file.bufferedWriter().use { w ->

        w.appendLine(
            pad("ID", 4) +
                    pad("Pn", 4) +
                    pad("p-value", 12) +
                    pad("AUC", 8) +
                    pad("area", 15) +
                    "plots"
        )

        w.appendLine("-".repeat(100))

        for (r in rows) {

            val plots = flattenLabel(r.label)

            val line = buildString {
                append(pad(r.id.toString(), 4))
                append(pad(r.parentId?.toString() ?: "-", 4))
                append(pad(formatP(r.pValue), 12))
                append(pad(formatAuc(r.auc), 8))
                append(pad(formatArea(r.area), 15))
                append(plots)
            }

            w.appendLine(line)
        }
    }
}

// VERSION MEDIUM: metrics on top row, rule and plots in the rows below
fun writeTxtMedium(rows: List<ExportRuleRow>, file: File) {

    file.bufferedWriter().use { w ->

        // Header
        w.appendLine(
            pad("ID", 4) +
                    pad("Pn", 4) +
                    pad("p-value", 12) +
                    pad("AUC", 8) +
                    pad("area", 12)
        )

        w.appendLine("-".repeat(60))

        for (r in rows) {

            val plots = flattenLabel(r.label)

            val headerLine = buildString {
                append(pad(r.id.toString(), 4))
                append(pad(r.parentId?.toString() ?: "-", 4))
                append(pad(formatP(r.pValue), 12))
                append(pad(formatAuc(r.auc), 8))
                append(pad(formatArea(r.area), 12))
            }

            w.appendLine(headerLine)

            w.appendLine("    " + r.rule)

            if (plots.isNotBlank()) {
                w.appendLine("    $plots")
            }
            w.appendLine(SEPARATOR)
        }
    }
}

// VERSION HEAVY: use tree dir structure
fun writeTxtHeavy(
    root: RuleTreeNode,
    header: List<String>,
    file: File
) {

    fun getAttrName(idx: Int): String =
        header.getOrNull(idx) ?: "attr#$idx"

    fun buildRule(node: RuleTreeNode): String {
        val step = node.steps.lastOrNull() ?: return ""

        val attrs = (step.prefix + step.addition)
            .map { getAttrName(it) }

        return attrs.joinToString(" ∧ ") + " -> y"
    }

    fun dfs(node: RuleTreeNode, depth: Int, w: BufferedWriter) {

        if (node.additionAttrIndex != null) {

            val indent = "    ".repeat(depth)

            val rule = buildRule(node)
            val plots = flattenLabel(node.label)

            w.appendLine(indent + rule)

            if (plots.isNotBlank()) {
                w.appendLine("$indent    $plots")
            }

            w.appendLine(indent + SEPARATOR)

        }

        for (child in node.children) {
            dfs(child, depth + 1, w)
        }
    }

    file.bufferedWriter().use { w ->
        dfs(root, depth = -1, w) // skip artificial root
    }
}

fun buildPrefix(node: RuleTreeNode): String {

    val parts = mutableListOf<String>()
    var current: RuleTreeNode? = node

    while (current?.parent != null && current.parent?.additionAttrIndex != null) {

        val parent = current.parent!!

        val isLast = parent.children.lastOrNull() === current

        parts += if (isLast) "└─" else "├─"

        // 🔥 this is the key: track vertical continuation
        current = parent
    }

    val prefixParts = parts.reversed().toMutableList()

    // 🔥 now fix vertical lines
    var nodeCursor: RuleTreeNode? = node.parent
    for (i in prefixParts.indices.reversed()) {

        val parent = nodeCursor?.parent
        if (parent != null && parent.additionAttrIndex != null) {

            val isLast = parent.children.lastOrNull() === nodeCursor

            if (!isLast) {
                prefixParts[i] = "│  "
            } else {
                prefixParts[i] = "   "
            }
        }

        nodeCursor = parent
    }

    // last element should stay ├─ / └─
    if (prefixParts.isNotEmpty()) {
        prefixParts[prefixParts.lastIndex] = parts.first()
    }

    return prefixParts.joinToString("")
}

fun writeTxtTreeInline(
    root: RuleTreeNode,
    rows: List<ExportRuleRow>,
    idToNode: Map<Int, RuleTreeNode>,
    file: File
) {

    val nodeToRow = rows.associateBy { row ->
        idToNode[row.id]!!
    }

    fun dfs(node: RuleTreeNode, w: BufferedWriter) {

        if (node.additionAttrIndex != null) {

            val r = nodeToRow[node] ?: return
            val prefix = buildPrefix(node)
            val plots = flattenLabel(r.label)

            if (prefix.isEmpty()) {
                w.appendLine("* $plots")
            } else {
                w.appendLine(prefix + plots)
            }
        }

        // 🔥 NO sorting — preserve original tree order
        for (child in node.children) {
            dfs(child, w)
        }
    }

    file.bufferedWriter().use { w ->
        for (child in root.children) {
            dfs(child, w)
            w.appendLine()
        }
    }
}
