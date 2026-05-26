package org.jetbrains.bio.qfarm.visualization

import org.jetbrains.bio.qfarm.evaluation.MedianFront
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.RandomAucBaseline
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evaluation.toPFSeries
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult

data class RenderedFrontPlots(
    val pfUrl: String,
    val rocUrl: String,
    val combinedUrl: String
)

fun renderFrontPlots(
    parentScoredFront: ScoredFront?,
    childScoredFront: ScoredFront,
    attrs: List<Int>,
    deLong: DeLongResult?,
    title: String,
    meta: Map<String, Any?> = emptyMap()
): RenderedFrontPlots? {
    return try {
        val isLevel1 = attrs.size == 1

        val hasRealParent =
            parentScoredFront?.front != null && !parentScoredFront.front.isEmpty

        val effectiveParentFront = if (hasRealParent) {
            parentScoredFront!!.front
        } else {
            MedianFront.scoredFront.front
        }

        val parentDataset = if (hasRealParent) {
            datasetWithHeader
        } else {
            MedianFront.datasetWithHeader
        }

        val parentName = if (hasRealParent) "Parent" else "Median"

        // ---------- PF plot ----------
        val pfSeries = buildList {
            add(toPFSeries(effectiveParentFront, parentName, parentDataset))
            add(toPFSeries(childScoredFront.front, "Child"))
        }

        val pfPlot = buildParetoFrontPlotCombined(
            pfSeries,
            title = title
        )

        val filename = attrsToFileName(attrs)

        val pfUrl = FrontStore.saveAndUrl(pfPlot, "${filename}_pf")
            ?: return null

        // ---------- ROC / AUC plot ----------
        val secondPlotUrl = if (isLevel1) {

            RandomAucBaseline.requireReady()

            val childAuc =
                (meta["auc"] as? Double)
                    ?: AUC.compute(datasetWithHeader.labels, childScoredFront.scores)

            val rawP = meta["randomAucP"] as? Double
            val adjP = meta["randomAucAdjustedP"] as? Double

            val aucDistPlot = buildRandomAucDistributionPlot(
                randomAucs = RandomAucBaseline.aucs,
                observedAuc = childAuc,
                title = buildString {
                    appendLine("Level-1 random AUC baseline")
                    append("Child AUC=%.4f".format(childAuc))

                    if (rawP != null) {
                        append(" | raw p=%.4g".format(rawP))
                    }

                    if (adjP != null) {
                        append(" | adj p=%.4g".format(adjP))
                    }
                }
            )

            FrontStore.saveAndUrl(aucDistPlot, "${filename}_auc_dist")
                ?: return null

        } else {

            val labels = datasetWithHeader.labels
            val effectiveParentScores = parentScoredFront!!.scores

            val rocSeries: List<Pair<String, DoubleArray>> = buildList {
                add(parentName to effectiveParentScores)
                add("Child" to childScoredFront.scores)
            }

            val effectiveDeLong = deLong
                ?: DeLong.compare(
                    labels,
                    effectiveParentScores,
                    childScoredFront.scores
                )

            val rocTitle = buildString {
                appendLine(
                    "AUC: $parentName=%.3f | Child=%.3f"
                        .format(effectiveDeLong.auc1, effectiveDeLong.auc2)
                )
                appendLine(
                    "Var: %.5f | %.5f"
                        .format(effectiveDeLong.variance1, effectiveDeLong.variance2)
                )
                appendLine("Cov: %.5f".format(effectiveDeLong.covariance))
                append(
                    "Δ=%.4f | z=%.2f | p=%.5f".format(
                        effectiveDeLong.auc2 - effectiveDeLong.auc1,
                        effectiveDeLong.zScore,
                        effectiveDeLong.pOneSided
                    )
                )
            }

            val rocPlot = buildROCPlot(
                rocSeries,
                labels,
                title = rocTitle
            )

            FrontStore.saveAndUrl(rocPlot, "${filename}_roc")
                ?: return null
        }

        // ---------- Combined wrapper ----------
        val combinedUrl = saveCombinedHtmlHorizontal(
            pfUrl = pfUrl,
            rocUrl = secondPlotUrl,
            filename = filename
        ) ?: return null

        RenderedFrontPlots(
            pfUrl = pfUrl,
            rocUrl = secondPlotUrl,
            combinedUrl = combinedUrl
        )

    } catch (t: Throwable) {
        println("${YELLOW}[⚠️ Couldn’t render plot: ${t.message}]${RESET}")
        null
    }
}
