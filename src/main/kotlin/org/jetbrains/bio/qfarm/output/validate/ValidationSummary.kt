package org.jetbrains.bio.qfarm.output.validate

import org.jetbrains.bio.qfarm.output.fronts.ExportRuleRow
import org.jetbrains.bio.qfarm.output.fronts.buildTreePrefix
import org.jetbrains.bio.qfarm.output.fronts.flattenLabel
import org.jetbrains.bio.qfarm.output.fronts.formatAuc
import org.jetbrains.bio.qfarm.output.fronts.formatDistance
import org.jetbrains.bio.qfarm.output.fronts.formatNumber
import org.jetbrains.bio.qfarm.output.fronts.formatP
import org.jetbrains.bio.qfarm.output.fronts.getDepth
import org.jetbrains.bio.qfarm.output.fronts.pad
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.params.hp
import java.io.File
import kotlin.math.roundToInt

data class Column(val name: String, val width: Int)

val VALIDATED_COLUMNS = listOf(
    Column("ROC p", 12),
    Column("AUC", 6),
    Column("dist", 12),
    Column("status", 20)
)


fun writeTxtValidated(
    rows: List<ExportRuleRow>,
    idToNode: Map<Int, RuleTreeNode>,
    originalRows: List<RuleTreeRow>,
    datasetName: String,
    previousRunName: String,
    file: File
) {
    val rhs =
        "${hp.rightAttribute} ∈ " +
                "[${rightGene.pLeft.roundToInt()}%, ${rightGene.pRight.roundToInt()}%], " +
                "i.e. [${formatNumber(rightGene.lowerBound)}, ${formatNumber(rightGene.upperBound)}]"

    val originalMap = originalRows.associateBy {
        it.rule.sorted().joinToString(",")
    }

    // -------------------------------------------------------------------------
    // Build tree
    // -------------------------------------------------------------------------

    val childrenByParent = rows.groupBy { it.parentId }
    val byId = rows.associateBy { it.id }

    val roots = childrenByParent[null].orEmpty()

    val maxDepth = rows.maxOfOrNull { row ->
        getDepth(row, byId)
    } ?: 0

    // Same tree sizing as writeTxtLight().
    //
    // ├──             = 4
    // │   ├──         = 8
    // │   │   └──     = 12
    val treeWidth = maxOf(
        "Tree".length,
        "START".length,
        maxDepth * 4
    )

    val rocPWidth = VALIDATED_COLUMNS[0].width
    val aucWidth = VALIDATED_COLUMNS[1].width
    val distWidth = VALIDATED_COLUMNS[2].width
    val statusWidth = VALIDATED_COLUMNS[3].width

    // Equal spacing between all columns.
    val columnGap = 4

    // Exact character position at which the plots column begins.
    // This is also used for the PREVIOUS RUN FRONT line.
    val plotsStartColumn =
        treeWidth +
                columnGap +
                rocPWidth +
                columnGap +
                aucWidth +
                columnGap +
                distWidth +
                columnGap +
                statusWidth +
                columnGap

    val separatorWidth = plotsStartColumn + 60

    file.bufferedWriter().use { w ->

        // ---------------------------------------------------------------------
        // META
        // ---------------------------------------------------------------------

        w.appendLine(
            "dataset: $datasetName    |    " +
                    "prev run: $previousRunName    |    " +
                    "RHS: $rhs"
        )

        w.appendLine("-".repeat(separatorWidth))

        // ---------------------------------------------------------------------
        // HEADER
        // ---------------------------------------------------------------------

        val header = buildString {
            append(pad("Tree", treeWidth))
            append(" ".repeat(columnGap))

            append(pad("ROC p", rocPWidth))
            append(" ".repeat(columnGap))

            append(pad("AUC", aucWidth))
            append(" ".repeat(columnGap))

            append(pad("dist", distWidth))
            append(" ".repeat(columnGap))

            append(pad("status", statusWidth))
            append(" ".repeat(columnGap))

            append("plots")
        }

        w.appendLine(header)
        w.appendLine("-".repeat(separatorWidth))

        // ---------------------------------------------------------------------
        // Recursive tree writer
        // ---------------------------------------------------------------------

        fun writeSubtree(
            r: ExportRuleRow,
            treeLabel: String,
            ancestorLastStates: List<Boolean>
        ) {
            val node = idToNode[r.id]

            if (node != null) {
                val meta = node.steps.lastOrNull()?.meta

                val dist =
                    meta?.get("distributionDistance") as? Double

                val failure =
                    meta?.get("failure") as? String ?: "OK"

                val validated =
                    meta?.get("validation") as? Boolean ?: true

                val status =
                    if (validated) "OK" else failure

                val key =
                    collectAttrs(node)
                        .sorted()
                        .joinToString(",")

                val original = originalMap[key]

                val prevPlots =
                    flattenLabel(original?.label)

                val rowColor =
                    if (validated) {
                        Ansi.GREEN
                    } else {
                        Ansi.RED
                    }

                val plots = when {
                    failure.startsWith("DIST_FAIL") -> {
                        flattenLabel(r.label)
                    }

                    failure.startsWith("MISSING") -> {
                        val expectedAttrs = collectAttrs(node)

                        buildPlotsWithMissing(
                            r.label,
                            expectedAttrs,
                            rowColor
                        )
                    }

                    else -> {
                        flattenLabel(r.label)
                    }
                }

                // -------------------------------------------------------------
                // Prepare fixed-width columns
                // -------------------------------------------------------------

                var rocPColumn =
                    pad(formatP(r.pValue), rocPWidth)

                val aucColumn =
                    pad(formatAuc(r.auc), aucWidth)

                var distColumn =
                    pad(formatDistance(dist), distWidth)

                val statusColumn =
                    pad(status, statusWidth)

                // -------------------------------------------------------------
                // Apply highlighting
                // -------------------------------------------------------------

                if (failure.startsWith("ROC_FAIL")) {
                    rocPColumn =
                        highlight(rocPColumn, rowColor)
                }

                if (failure.startsWith("DIST_FAIL")) {
                    distColumn =
                        highlight(distColumn, rowColor)
                }

                // -------------------------------------------------------------
                // Main row
                // -------------------------------------------------------------

                val rowStr = buildString {
                    append(pad(treeLabel, treeWidth))
                    append(" ".repeat(columnGap))

                    append(rocPColumn)
                    append(" ".repeat(columnGap))

                    append(aucColumn)
                    append(" ".repeat(columnGap))

                    append(distColumn)
                    append(" ".repeat(columnGap))

                    append(statusColumn)
                    append(" ".repeat(columnGap))

                    append(plots)
                }

                w.appendLine(
                    "$rowColor$rowStr${Ansi.RESET}"
                )

                // -------------------------------------------------------------
                // EXTRA LINE — DIST FAIL
                //
                // prevPlots begins at exactly the same character position
                // as the normal plots column above.
                // -------------------------------------------------------------

                if (failure.startsWith("DIST_FAIL")) {
                    val txt = "     └── PREVIOUS RUN FRONT: "

                    val prefix =
                        if (txt.length < plotsStartColumn) {
                            txt.padEnd(plotsStartColumn)
                        } else {
                            "$txt "
                        }

                    w.appendLine(
                        prefix + prevPlots
                    )
                }
            }

            // -----------------------------------------------------------------
            // Children
            // -----------------------------------------------------------------

            val children =
                childrenByParent[r.id].orEmpty()

            children.forEachIndexed { index, child ->

                val isLastChild =
                    index == children.lastIndex

                val prefix =
                    buildTreePrefix(ancestorLastStates)

                val connector =
                    if (isLastChild) {
                        "└── "
                    } else {
                        "├── "
                    }

                writeSubtree(
                    r = child,
                    treeLabel = prefix + connector,
                    ancestorLastStates =
                        ancestorLastStates + isLastChild
                )
            }
        }

        // ---------------------------------------------------------------------
        // Write roots
        // ---------------------------------------------------------------------

        roots.forEachIndexed { index, root ->

            writeSubtree(
                r = root,
                treeLabel = "START",
                ancestorLastStates = emptyList()
            )

            if (index != roots.lastIndex) {
                w.appendLine()
            }
        }
    }
}
