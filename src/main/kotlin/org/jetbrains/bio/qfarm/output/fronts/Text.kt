package org.jetbrains.bio.qfarm.output.fronts

import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.util.hp
import java.io.File
import kotlin.math.roundToInt

// VERSION LIGHT: all in one row, no rule, only plots
fun writeTxtLight(rows: List<ExportRuleRow>, file: File) {
    val rhs = "${hp.rightAttribute} ∈ [${rightGene.pLeft.roundToInt()}%, ${rightGene.pRight.roundToInt()}%], i.e. [${formatNumber(rightGene.lowerBound)}, ${formatNumber(rightGene.upperBound)}]"

    file.bufferedWriter().use { w ->

        w.appendLine(
            pad("ID", 4) +
                    pad("Pn", 4) +
                    pad("p-value", 12) +
                    pad("AUC", 8) +
                    pad("area", 15) +
                    "plots    (RHS: $rhs)"
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
