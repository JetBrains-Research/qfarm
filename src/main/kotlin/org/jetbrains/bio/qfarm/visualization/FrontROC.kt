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
import org.jetbrains.letsPlot.geom.geomHistogram
import org.jetbrains.letsPlot.geom.geomVLine
import org.jetbrains.letsPlot.label.labs


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

    series.forEachIndexed { _, (name, scores) ->

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

fun buildRandomAucDistributionPlot(
    randomAucs: List<Double>,
    observedAuc: Double,
    title: String
): Plot {
    require(randomAucs.isNotEmpty()) {
        "Random AUC baseline is empty."
    }

    val data = mapOf(
        "auc" to randomAucs
    )

    return letsPlot(data) {
        x = "auc"
    } +
            geomHistogram(
                bins = 20,
                alpha = 0.75,
                fill = "#4C78A8",
                color = "#2F4B7C"
            ) +
            geomVLine(
                xintercept = observedAuc,
                linetype = "dashed",
                size = 1.5,
                color = "#F28E2B"
            ) +
            ggtitle(
                "$title\n" +
                        "Blue histogram = random baseline AUCs | " +
                        "Orange dashed line = observed child AUC"
            ) +
            labs(
                x = "AUC",
                y = "Count"
            )
}
