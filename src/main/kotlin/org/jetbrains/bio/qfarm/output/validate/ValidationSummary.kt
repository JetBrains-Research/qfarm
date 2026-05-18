package org.jetbrains.bio.qfarm.output.validate

import org.jetbrains.bio.qfarm.output.fronts.ExportRuleRow
import org.jetbrains.bio.qfarm.output.fronts.flattenLabel
import org.jetbrains.bio.qfarm.output.fronts.formatArea
import org.jetbrains.bio.qfarm.output.fronts.formatAuc
import org.jetbrains.bio.qfarm.output.fronts.formatDistance
import org.jetbrains.bio.qfarm.output.fronts.formatNumber
import org.jetbrains.bio.qfarm.output.fronts.formatP
import org.jetbrains.bio.qfarm.output.fronts.pad
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.util.hp
import java.io.File
import kotlin.math.roundToInt

data class Column(val name: String, val width: Int)

val COLUMNS = listOf(
    Column("ID", 6),
    Column("Pn", 6),
    Column("ROC p", 12),
    Column("AUC", 10),
    Column("area", 10),
    Column("dist", 12),
    Column("status", 20)
)

val PREFIX_WIDTH = COLUMNS.sumOf { it.width }
val SEPARATOR_WIDTH = PREFIX_WIDTH + 60 // extra space for plots

fun writeTxtValidated(
    rows: List<ExportRuleRow>,
    idToNode: Map<Int, RuleTreeNode>,
    originalRows: List<RuleTreeRow>,
    datasetName: String,
    previousRunName: String,
    file: File
) {

    val rhs = "${hp.rightAttribute} ∈ [${rightGene.pLeft.roundToInt()}%, ${rightGene.pRight.roundToInt()}%], i.e. [${formatNumber(rightGene.lowerBound)}, ${formatNumber(rightGene.upperBound)}]"

    val originalMap = originalRows.associateBy {
        it.rule.sorted().joinToString(",")
    }

    file.bufferedWriter().use { w ->

        // ---------------- META ----------------
        w.appendLine("dataset: $datasetName    |    prev run: $previousRunName    |    RHS: $rhs")
        w.appendLine("-".repeat(SEPARATOR_WIDTH))

        // ---------------- HEADER ----------------
        w.appendLine(renderRow(COLUMNS.map { it.name }) + "plots")
        w.appendLine("-".repeat(SEPARATOR_WIDTH))

        // ---------------- ROWS ----------------
        for (r in rows) {

            val node = idToNode[r.id] ?: continue
            val meta = node.steps.lastOrNull()?.meta

            val dist = meta?.get("distributionDistance") as? Double
            val failure = meta?.get("failure") as? String ?: "OK"
            val validated = meta?.get("validation") as? Boolean ?: true

            val status = if (validated) "OK" else failure

            val key = collectAttrs(node).sorted().joinToString(",")
            val original = originalMap[key]
            val prevPlots = flattenLabel(original?.label)

            val rowColor = if (validated) Ansi.GREEN else Ansi.RED

            val plots = when {
                failure.startsWith("DIST_FAIL") -> flattenLabel(r.label)

                failure.startsWith("MISSING") -> {
                    val expectedAttrs = collectAttrs(node)
                    buildPlotsWithMissing(r.label, expectedAttrs, rowColor)
                }

                else -> flattenLabel(r.label)
            }

            val values = listOf(
                r.id.toString(),
                r.parentId?.toString() ?: "-",
                formatP(r.pValue),   // ROC p
                formatAuc(r.auc),
                formatArea(r.area),
                formatDistance(dist), // smooth Spearman distance
                status
            )

            val padded = values.zip(COLUMNS).map { (v, col) ->
                pad(v, col.width)
            }.toMutableList()

            // Apply highlighting
            when {
                failure.startsWith("ROC_FAIL") -> {
                    padded[2] = highlight(padded[2], rowColor)
                }

                failure.startsWith("DIST_FAIL") -> {
                    padded[5] = highlight(padded[5], rowColor)
                }
            }

            val rowStr = padded.joinToString("") + plots

            val finalRow = "$rowColor$rowStr${Ansi.RESET}"

            w.appendLine(finalRow)

            // ---------------- EXTRA LINE (DIST FAIL) ----------------
            if (failure.startsWith("DIST_FAIL")) {
                val txt = "     └── PREVIOUS RUN FRONT: "
                val prefixWidth =
                    PREFIX_WIDTH - txt.length

                val indent = " ".repeat(prefixWidth)

                w.appendLine(
                    "$txt$indent$prevPlots"
                )
            }
        }
    }
}
