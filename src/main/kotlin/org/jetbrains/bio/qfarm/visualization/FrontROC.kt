package org.jetbrains.bio.qfarm.visualization

import org.jetbrains.bio.qfarm.evaluation.fronts.computeROC
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleColorManual
import org.jetbrains.letsPlot.geom.geomRect
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

/** 20-bin histogram of a random-AUC baseline: bin edges and counts. */
class AucHistogram(val lo: List<Double>, val hi: List<Double>, val count: List<Int>)

private object AucHistogramCache {
    private var source: List<Double>? = null
    private var histogram: AucHistogram? = null

    /** Bins the baseline once; every node's plot reuses the same bins (only its AUC line differs). */
    @Synchronized
    fun of(randomAucs: List<Double>, bins: Int): AucHistogram {
        if (source !== randomAucs || histogram == null || histogram!!.count.size != bins) {
            histogram = binAucs(randomAucs, bins)
            source = randomAucs
        }
        return histogram!!
    }
}

internal fun binAucs(values: List<Double>, bins: Int): AucHistogram {
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    for (v in values) { if (v < min) min = v; if (v > max) max = v }
    val width = if (max > min) (max - min) / bins else 1.0
    val counts = IntArray(bins)
    for (v in values) counts[((v - min) / width).toInt().coerceIn(0, bins - 1)]++
    return AucHistogram(
        lo = List(bins) { min + it * width },
        hi = List(bins) { min + (it + 1) * width },
        count = counts.toList()
    )
}

fun buildRandomAucDistributionPlot(
    randomAucs: List<Double>,
    observedAuc: Double,
    title: String
): Plot {
    require(randomAucs.isNotEmpty()) {
        "Random AUC baseline is empty."
    }

    // the baseline (up to millions of values) is binned once, not once per node
    val h = AucHistogramCache.of(randomAucs, bins = 20)
    val data = mapOf(
        "lo" to h.lo,
        "hi" to h.hi,
        "count" to h.count,
        "zero" to List(h.count.size) { 0 }
    )

    return letsPlot(data) +
            geomRect(
                alpha = 0.75,
                fill = "#4C78A8",
                color = "#2F4B7C"
            ) {
                xmin = "lo"
                xmax = "hi"
                ymin = "zero"
                ymax = "count"
            } +
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
