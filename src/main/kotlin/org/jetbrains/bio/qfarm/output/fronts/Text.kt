package org.jetbrains.bio.qfarm.output.fronts

import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.params.hp
import java.io.BufferedWriter
import java.io.File
import kotlin.math.roundToInt

fun writeTxtLight(rows: List<ExportRuleRow>, file: File) {
    val rhs =
        "${hp.rightAttribute} ∈ " +
                "[${rightGene.pLeft.roundToInt()}%, ${rightGene.pRight.roundToInt()}%], " +
                "i.e. [${formatNumber(rightGene.lowerBound)}, ${formatNumber(rightGene.upperBound)}]"

    val childrenByParent = rows.groupBy { it.parentId }
    val byId = rows.associateBy { it.id }

    val roots = childrenByParent[null].orEmpty()

    val maxDepth = rows.maxOfOrNull { row ->
        getDepth(row, byId)
    } ?: 0

    // A node at depth d uses d * 4 characters:
    //
    // ├──             = 4
    // │   ├──         = 8
    // │   │   └──     = 12
    //
    // Also make sure "Tree" and "START" fit.
    val treeWidth = maxOf(
        "Tree".length,
        "START".length,
        maxDepth * 4
    )

    val pValueWidth = 12
    val aucWidth = 6
    val columnGap = 4

    file.bufferedWriter().use { w ->

        val header = buildString {
            append(pad("Tree", treeWidth))

            append(center("DeLong p-val", pValueWidth))
            append(" ".repeat(columnGap))

            append(pad("AUC", aucWidth))
            append(" ".repeat(columnGap))

            append("plots    (RHS: $rhs)")
        }

        w.appendLine(header)
        w.appendLine("-".repeat(117))

        roots.forEachIndexed { index, root ->

            writeTreeRow(
                row = root,
                writer = w,
                treeWidth = treeWidth,
                pValueWidth = pValueWidth,
                aucWidth = aucWidth,
                columnGap = columnGap,
                treeLabel = "START"
            )

            writeChildren(
                parent = root,
                childrenByParent = childrenByParent,
                writer = w,
                treeWidth = treeWidth,
                pValueWidth = pValueWidth,
                aucWidth = aucWidth,
                columnGap = columnGap,
                ancestorLastStates = emptyList()
            )

            if (index != roots.lastIndex) {
                w.appendLine()
            }
        }
    }
}


private fun writeChildren(
    parent: ExportRuleRow,
    childrenByParent: Map<Int?, List<ExportRuleRow>>,
    writer: BufferedWriter,
    treeWidth: Int,
    pValueWidth: Int,
    aucWidth: Int,
    columnGap: Int,
    ancestorLastStates: List<Boolean>
) {
    val children = childrenByParent[parent.id].orEmpty()

    children.forEachIndexed { index, child ->

        val isLastChild = index == children.lastIndex

        val prefix = buildTreePrefix(ancestorLastStates)

        val connector =
            if (isLastChild) {
                "└── "
            } else {
                "├── "
            }

        writeTreeRow(
            row = child,
            writer = writer,
            treeWidth = treeWidth,
            pValueWidth = pValueWidth,
            aucWidth = aucWidth,
            columnGap = columnGap,
            treeLabel = prefix + connector
        )

        writeChildren(
            parent = child,
            childrenByParent = childrenByParent,
            writer = writer,
            treeWidth = treeWidth,
            pValueWidth = pValueWidth,
            aucWidth = aucWidth,
            columnGap = columnGap,
            ancestorLastStates = ancestorLastStates + isLastChild
        )
    }
}


private fun buildTreePrefix(
    ancestorLastStates: List<Boolean>
): String {
    return buildString {
        ancestorLastStates.forEach { ancestorWasLast ->
            if (ancestorWasLast) {
                append("    ")
            } else {
                append("│   ")
            }
        }
    }
}


private fun writeTreeRow(
    row: ExportRuleRow,
    writer: BufferedWriter,
    treeWidth: Int,
    pValueWidth: Int,
    aucWidth: Int,
    columnGap: Int,
    treeLabel: String
) {
    val plots = flattenLabel(row.label)

    val line = buildString {
        append(pad(treeLabel, treeWidth))

        // Center the p-value underneath "DeLong p-val".
        append(center(formatP(row.pValue), pValueWidth))
        append(" ".repeat(columnGap))

        append(pad(formatAuc(row.auc), aucWidth))
        append(" ".repeat(columnGap))

        append(plots)
    }

    writer.appendLine(line)
}


private fun getDepth(
    row: ExportRuleRow,
    byId: Map<Int, ExportRuleRow>
): Int {
    var depth = 0
    var parentId = row.parentId

    val visited = mutableSetOf<Int>()

    while (parentId != null) {
        if (!visited.add(parentId)) {
            break
        }

        val parent = byId[parentId] ?: break

        depth++
        parentId = parent.parentId
    }

    return depth
}


private fun center(
    value: String,
    width: Int
): String {
    if (value.length >= width) {
        return value
    }

    val totalPadding = width - value.length
    val leftPadding = totalPadding / 2
    val rightPadding = totalPadding - leftPadding

    return " ".repeat(leftPadding) +
            value +
            " ".repeat(rightPadding)
}
