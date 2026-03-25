package org.jetbrains.bio.qfarm

import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleColorManual

data class ROCPoint(val fpr: Double, val tpr: Double)

fun computeROC(labels: IntArray, scores: DoubleArray): List<ROCPoint> {
    require(labels.size == scores.size)

    val pairs = labels.indices
        .map { i -> scores[i] to labels[i] }
        .sortedByDescending { it.first }

    val P = labels.count { it == 1 }.toDouble()
    val N = labels.count { it == 0 }.toDouble()

    var tp = 0.0
    var fp = 0.0

    val roc = mutableListOf<ROCPoint>()
    roc += ROCPoint(0.0, 0.0)

    for ((_, label) in pairs) {
        if (label == 1) tp++ else fp++

        val tpr = if (P > 0) tp / P else 0.0
        val fpr = if (N > 0) fp / N else 0.0

        roc += ROCPoint(fpr, tpr)
    }

    roc += ROCPoint(1.0, 1.0)
    return roc
}

fun buildROCPlot(
    series: List<Pair<String, DoubleArray>>, // name → scores
    labels: IntArray,
    title: String = "ROC Curves"
): org.jetbrains.letsPlot.intern.Plot {

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
