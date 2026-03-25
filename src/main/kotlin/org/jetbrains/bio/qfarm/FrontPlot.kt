package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.statistics.delong.AUC
import java.io.File

val PLOT_WIDTH = 800
val PLOT_HEIGHT = 650

fun buildPalette(seriesNames: List<String>): Pair<List<String>, List<String>> {
    val palette = when (seriesNames.size) {
        0 -> emptyList()
        1 -> listOf("#1f77b4")
        2 -> listOf("#1f77b4", "#ff7f0e")
        else -> (0 until seriesNames.size).map { i ->
            "hsl(${(360.0 / seriesNames.size * i).toInt()},70%,50%)"
        }
    }
    return seriesNames to palette.take(seriesNames.size)
}

fun attrsToFileName(attrs: List<Int>): String {
    return attrs.joinToString("_AND_") { idx ->
        val raw = columnNames.getOrNull(idx) ?: "X$idx"

        // sanitize for filesystem
        raw
            .trim()
            .replace("\\s+".toRegex(), "_")        // spaces → _
            .replace("[^a-zA-Z0-9._-]".toRegex(), "") // remove weird chars
    }
}

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

        val aucParent = AUC.compute(labels, effectiveParentScores)
        val aucChild = AUC.compute(labels, childScoredFront.scores)

        val rocTitle = buildString {
            append("$parentName AUC = %.3f".format(aucParent))
            append("\nChild AUC = %.3f".format(aucChild))
        }

        val rocPlot = buildROCPlot(rocSeries, labels, title = rocTitle)

        val rocUrl = FrontStore.saveAndUrl(rocPlot, "${filename}_roc")
            ?: return null

        // ---------- Combine ----------
        saveCombinedHtmlHorizontal(pfUrl, rocUrl, filename)
            ?.let { it.substring(it.indexOf(plotDir)) }

    } catch (t: Throwable) {
        println("$YELLOW[⚠️ Couldn’t render plot: ${t.message}]$RESET")
        null
    }
}

fun saveCombinedHtmlHorizontal(
    pfUrl: String,
    rocUrl: String,
    filename: String
): String? {
    return try {
        val out = File("$plots_file_path/$filename.html")

        val html = """
            <html>
            <head>
                <title>$filename</title>
            </head>
            <body style="font-family: sans-serif; margin:0; padding:0;">
                
                <div style="display:flex; width:100%; height:100vh;">
                    
                    <div style="flex:1; padding:10px;">
                        <h3 style="text-align:center;">Pareto Front</h3>
                        <iframe src="$pfUrl" width="100%" height="90%" style="border:none;"></iframe>
                    </div>
                    
                    <div style="flex:1; padding:10px;">
                        <h3 style="text-align:center;">ROC Curves</h3>
                        <iframe src="$rocUrl" width="100%" height="90%" style="border:none;"></iframe>
                    </div>
                
                </div>
                
            </body>
            </html>
        """.trimIndent()

        out.writeText(html)
        out.toURI().toString()

    } catch (t: Throwable) {
        println("$YELLOW[⚠️ Failed to save combined HTML: ${t.message}]$RESET")
        null
    }
}
