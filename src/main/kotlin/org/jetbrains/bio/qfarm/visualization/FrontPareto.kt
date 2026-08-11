package org.jetbrains.bio.qfarm.visualization

import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.random.MedianFront
import org.jetbrains.bio.qfarm.evaluation.fronts.PFSeries
import org.jetbrains.bio.qfarm.util.compactRuleString
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.numericRuleString
import org.jetbrains.bio.qfarm.util.stripAnsi
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomPolygon
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.guides
import org.jetbrains.letsPlot.scale.scaleColorManual
import org.jetbrains.letsPlot.tooltips.layerTooltips
import kotlin.math.max


fun buildParetoFrontPlotCombined(
    seriesList: List<PFSeries>,
    dataset: DatasetWithHeader = datasetWithHeader,
    title: String = "Pareto Front (Gen ${hp.maxGenFull})"
): Plot {
    // Helper: extract sorted (x=SupportX, y=Confidence, rule) triples per series
    data class SeriesPoint(
        val x: Int,
        val y: Double,
        val rulePct: String,   // old rule text with percentiles (used as title)
        val ruleNum: String,   // new numeric multi-line rule (tooltip body)
        val idx: Int           // index in original front for metrics lookup
    )

    fun extractSortedPoints(
        s: PFSeries,
        dataset: DatasetWithHeader
    ): List<SeriesPoint> {
        return s.front.mapIndexedNotNull { idx, pt ->
            val f = pt.fitness().data()

            val rawSupport = f[0]

            if (rawSupport < hp.minSupport || rawSupport > hp.maxSupport) return@mapIndexedNotNull null

            val x = rawSupport.toInt()

            val y = f[1]

            val rulePct = compactRuleString(columnNames, pt.genotype())
                .let { stripAnsi(it).split(")").joinToString(")\n") }

            val ruleNum = numericRuleString(dataset.header, pt.genotype())

            SeriesPoint(
                x = x,
                y = y,
                rulePct = rulePct,
                ruleNum = ruleNum,
                idx = idx
            )
        }.sortedBy { it.x }
    }

    fun datasetForSeries(
        s: PFSeries,
        defaultDataset: DatasetWithHeader
    ): DatasetWithHeader {
        return if (s.name.equals("Median", ignoreCase = true)) {
            MedianFront.datasetWithHeader
        } else {
            defaultDataset
        }
    }

    val (breaks, palette) = buildPalette(seriesList.map { it.name })

    // Tooltip (Y is Confidence)
    val tt = layerTooltips()
        .title("@rulePct")
        .format("@ratio", ".3f")
        .line("@ruleNum")
        .line("TP=@TP  FP=@FP \n TN=@TN  FN=@FN")
        .line("Type1 vs Type2 error =@ratio")


    var plot = letsPlot() +
            ggtitle(title) +
            scaleColorManual(breaks = breaks, values = palette) +
            guides(color = "none") +
            ggsize(PLOT_WIDTH, PLOT_HEIGHT)

    // ---------------- Between-front fill clipped to [xL, xR] with vertical edges ----------------

    val parentSeries = seriesList.find { it.name.equals("Parent", ignoreCase = true) }
    val childSeries  = seriesList.firstOrNull { !it.name.equals("Parent", ignoreCase = true) }
    if (parentSeries != null && childSeries != null) {
        val pDataset = datasetForSeries(parentSeries, dataset)

        val cDataset = datasetForSeries(childSeries, dataset)

        val pPts = extractSortedPoints(parentSeries, pDataset)
        val cPts = extractSortedPoints(childSeries, cDataset)

        val px = pPts.map { it.x }
        val py = pPts.map { it.y }
        val cx = cPts.map { it.x }
        val cy = cPts.map { it.y }


        if (px.isNotEmpty() && py.isNotEmpty() && cx.isNotEmpty() && cy.isNotEmpty()) {

            fun interpY(xs: List<Int>, ys: List<Double>, x: Int): Double? {
                if (xs.isEmpty()) return null
                if (xs.size == 1) return if (x == xs[0]) ys[0] else null
                if (x < xs.first() || x > xs.last()) return null
                var i = 0
                while (i < xs.lastIndex && xs[i + 1] < x) i++
                val x0 = xs[i]; val y0 = ys[i]
                val x1 = xs[i + 1]; val y1 = ys[i + 1]
                val dx = x1 - x0
                return if (dx == 0) max(y0, y1) else y0 + (x - x0) * (y1 - y0) / dx
            }

            // Overlap window
            val xL = maxOf(cx.first(), px.first())
            val xR = minOf(cx.last(),  px.last())
            if (xL < xR) {
                val xsBreaks = buildSet {
                    add(xL); add(xR)
                    cx.forEach { if (it in xL..xR) add(it) }
                    px.forEach { if (it in xL..xR) add(it) }
                }.toMutableList().sorted()

                val oX = mutableListOf<Int>(); val oY = mutableListOf<Double>(); val oGrp = mutableListOf<String>()
                val bX = mutableListOf<Int>(); val bY = mutableListOf<Double>(); val bGrp = mutableListOf<String>()
                var gidOrange = 0
                var gidBlue = 0

                for (k in 0 until xsBreaks.lastIndex) {
                    val a = xsBreaks[k]
                    val b = xsBreaks[k + 1]
                    val ycA = interpY(cx, cy, a) ?: continue
                    val ycB = interpY(cx, cy, b) ?: continue
                    val ypA = interpY(px, py, a) ?: continue
                    val ypB = interpY(px, py, b) ?: continue

                    val width = b - a
                    if (width <= 0.0) continue

                    val areaChild  = width * (ycA + ycB) / 2.0
                    val areaParent = width * (ypA + ypB) / 2.0

                    val tX = arrayListOf(a, b, b, a)
                    val tY = arrayListOf(ycA, ycB, ypB, ypA)

                    if (areaChild > areaParent) {
                        gidOrange++; val g = "o-$gidOrange"
                        repeat(4) { oGrp += g }; oX += tX; oY += tY
                    } else {
                        gidBlue++;  val g = "b-$gidBlue"
                        repeat(4) { bGrp += g }; bX += tX; bY += tY
                    }
                }

                if (oX.isNotEmpty()) {
                    val orangeData = mapOf("x" to oX, "y" to oY, "grp" to oGrp)
                    plot += geomPolygon(
                        data = orangeData,
                        fill = "#ff7f0e",
                        alpha = 0.20,
                        size = 0.0
                    ) { x = "x"; y = "y"; group = "grp" }
                }
                if (bX.isNotEmpty()) {
                    val blueData = mapOf("x" to bX, "y" to bY, "grp" to bGrp)
                    plot += geomPolygon(
                        data = blueData,
                        fill = "#1f77b4",
                        alpha = 0.20,
                        size = 0.0
                    ) { x = "x"; y = "y"; group = "grp" }
                }
            }
        }
    }

    // ---------------- Series: lines + points ----------------

    seriesList.forEachIndexed { _, s ->
        val sDataset = datasetForSeries(s, dataset)

        val pts = extractSortedPoints(s, sDataset)
        val n = pts.size
        if (n == 0) return@forEachIndexed

        val xs       = pts.map { it.x }
        val ys       = pts.map { it.y }
        val rulePcts = pts.map { it.rulePct }
        val ruleNums = pts.map { it.ruleNum }

        val m = s.metrics

        val tps = pts.map { m?.getOrNull(it.idx)?.tp ?: 0 }
        val fps = pts.map { m?.getOrNull(it.idx)?.fp ?: 0 }
        val tns = pts.map { m?.getOrNull(it.idx)?.tn ?: 0 }
        val fns = pts.map { m?.getOrNull(it.idx)?.fn ?: 0 }
        val ratios = pts.map { m?.getOrNull(it.idx)?.ratio ?: Double.NaN }

        // Series polyline (color legend)
        val lineData = mapOf(
            "SupportX"   to xs,
            "Confidence" to ys,
            "series"     to List(n) { s.name }
        )
        plot += geomLine(
            data = lineData,
            size = 1.1,
            alpha = 0.95
        ) { x = "SupportX"; y = "Confidence"; color = "series" }

        // Points
        val pointData = mapOf(
            "SupportX"   to xs,
            "Confidence" to ys,
            "rulePct"    to rulePcts,
            "ruleNum"    to ruleNums,
            "series"     to List(n) { s.name },
            "TP"         to tps,
            "FP"         to fps,
            "TN"         to tns,
            "FN"         to fns,
            "ratio"      to ratios
        )
        plot += geomPoint(
            data = pointData,
            size = 2.8,
            alpha = 0.95,
            tooltips = tt
        ) { x = "SupportX"; y = "Confidence"; color = "series" }
    }


    plot += xlab("SupportX") + ylab("Confidence")
    return plot
}
