package org.jetbrains.bio.qfarm.visualization

import org.jetbrains.bio.qfarm.evaluation.computeROC
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleColorManual


fun buildROCPlot(
    series: List<Pair<String, DoubleArray>>, // name → scores
    labels: IntArray,
    title: String = "ROC Curves"
): Plot {

    val seriesNames = series.map { it.first }
    val (breaks, palette) = buildPalette(seriesNames)

    var plot = letsPlot() +
            ggtitle(title) +
            xlab("False Positive Rate") +
            ylab("True Positive Rate") +
            scaleColorManual(breaks = breaks, values = palette) +
            ggsize(PLOT_WIDTH, PLOT_HEIGHT)

    series.forEachIndexed { i, (name, scores) ->

        val roc = computeROC(labels, scores)

        val data = mapOf(
            "fpr" to roc.map { it.fpr },
            "tpr" to roc.map { it.tpr },
            "series" to List(roc.size) { name }
        )

        plot += geomLine(
            data = data,
            size = 1.2
        ) {
            x = "fpr"
            y = "tpr"
            color = "series"
        }
    }

    // Diagonal baseline
    plot += geomLine(
        data = mapOf(
            "fpr" to listOf(0.0, 1.0),
            "tpr" to listOf(0.0, 1.0)
        ),
        linetype = 2,
        alpha = 0.6
    ) {
        x = "fpr"
        y = "tpr"
    }

    return plot
}
