package org.jetbrains.bio.qfarm.evaluation.random

import org.jetbrains.letsPlot.geom.geomDensity
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillManual
import org.jetbrains.letsPlot.scale.scaleColorManual

fun buildAucBaselineComparisonPlot(
    evolvedAucs: List<Double>,
    analyticalAucs: List<Double>,
    title: String = "Random AUC baseline comparison"
): Plot {
    require(evolvedAucs.isNotEmpty()) { "evolvedAucs must not be empty" }
    require(analyticalAucs.isNotEmpty()) { "analyticalAucs must not be empty" }

    val aucValues = evolvedAucs + analyticalAucs

    val source = List(evolvedAucs.size) { "Evolved random columns" } +
            List(analyticalAucs.size) { "Analytical shuffled windows" }

    val data = mapOf(
        "auc" to aucValues,
        "source" to source
    )

    return letsPlot(data) {
        x = "auc"
        color = "source"
        fill = "source"
    } +
            geomDensity(
                alpha = 0.25,
                size = 1.2
            ) +
            scaleFillManual(
                values = mapOf(
                    "Evolved random columns" to "#4C78A8",
                    "Analytical shuffled windows" to "#F28E2B"
                )
            ) +
            scaleColorManual(
                values = mapOf(
                    "Evolved random columns" to "#4C78A8",
                    "Analytical shuffled windows" to "#F28E2B"
                )
            ) +
            ggtitle(title) +
            labs(
                x = "AUC",
                y = "Density",
                fill = "Baseline",
                color = "Baseline"
            )
}
