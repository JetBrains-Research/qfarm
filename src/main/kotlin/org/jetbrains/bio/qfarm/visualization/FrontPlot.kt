package org.jetbrains.bio.qfarm.visualization

import org.jetbrains.bio.qfarm.evaluation.MedianFront
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evaluation.toPFSeries
import org.jetbrains.bio.qfarm.statistics.delong.DeLong


fun renderFrontPlotUrl(
    parentScoredFront: ScoredFront?,
    childScoredFront: ScoredFront,
    attrs: List<Int>,
    title: String,
    randomFront: Boolean
): String? {
    return try {
        val hasRealParent = parentScoredFront?.front != null && !parentScoredFront.front.isEmpty

        val effectiveParentFront = if (hasRealParent) {
            parentScoredFront!!.front
        } else {
            MedianFront.scoredFront.front
        }

        val effectiveParentScores = if (hasRealParent) {
            parentScoredFront!!.scores
        } else {
            MedianFront.scoredFront.scores
        }

        val parentDataset = if (hasRealParent)
            datasetWithHeader
        else
            MedianFront.datasetWithHeader

        val parentName = if (hasRealParent) "Parent" else "Median"

        // ---------- PF plot ----------
        val pfSeries = buildList {
            add(toPFSeries(effectiveParentFront, parentName, parentDataset))
            add(toPFSeries(childScoredFront.front, if (randomFront) "Random" else "Child"))
        }

        val pfPlot = buildParetoFrontPlotCombined(
            pfSeries,
            title = title,
            randomFront = randomFront
        )

        val filename = attrsToFileName(attrs)
        val pfUrl = FrontStore.saveAndUrl(pfPlot, "${filename}_pf")
            ?: return null

        // ---------- ROC plot ----------
        val labels = datasetWithHeader.labels

        val rocSeries: List<Pair<String, DoubleArray>> = buildList {
            add(parentName to effectiveParentScores)
            add((if (randomFront) "Random" else "Child") to childScoredFront.scores)
        }

        val delong = DeLong.compare(labels, effectiveParentScores, childScoredFront.scores)

        val rocTitle = buildString {
            appendLine("AUC: $parentName=%.3f | Child=%.3f".format(delong.auc1, delong.auc2))
            appendLine("Var: %.5f | %.5f".format(delong.variance1, delong.variance2))
            appendLine("Cov: %.5f".format(delong.covariance))
            append("Δ=%.4f | z=%.2f | p=%.5f".format(
                delong.auc2 - delong.auc1,
                delong.zScore,
                delong.pOneSided
            ))
        }

        val rocPlot = buildROCPlot(rocSeries, labels, title = rocTitle)

        val rocUrl = FrontStore.saveAndUrl(rocPlot, "${filename}_roc")
            ?: return null

        // ---------- Combine ----------
        saveCombinedHtmlHorizontal(pfUrl, rocUrl, filename)
            ?.let { it.substring(it.indexOf(plotDir)) }

    } catch (t: Throwable) {
        println("${YELLOW}[⚠️ Couldn’t render plot: ${t.message}]${RESET}")
        null
    }
}
