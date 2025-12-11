package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import io.jenetics.Phenotype
import io.jenetics.ext.moea.Pareto
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomPolygon
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.guides
import org.jetbrains.letsPlot.scale.scaleColorManual
import org.jetbrains.letsPlot.tooltips.layerTooltips
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val plots_file_path = "$PLOTS_DIR/front_plots_$RUN_NAME"

data class PFSeries(
    val name: String,
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val metrics: Map<String, List<*>>? = null,
    val bestIndex: Int? = null          // optional "best" per series
)

fun toPFSeries(
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    seriesName: String,
    newDataset: DatasetWithHeader = datasetWithHeader,
): PFSeries {

    val rIdx = rightAttrIndex
    val rLo  = rightGene.lowerBound
    val rUp  = rightGene.upperBound
    val data = newDataset.data


    var bestScore = Double.POSITIVE_INFINITY
    var bestIdx   = -1

    val tpList = mutableListOf<Int>()
    val fpList = mutableListOf<Int>()
    val tnList = mutableListOf<Int>()
    val fnList = mutableListOf<Int>()
    val type1List = mutableListOf<Double>()
    val type2List = mutableListOf<Double>()
    val ratioList = mutableListOf<Double>()

    for ((i, pt) in front!!.withIndex()) {
        val gt: Genotype<AttributeGene> = pt.genotype()
        val lhs = gt[0] as RuleSideChromosome

        val active = lhs.filterNotNull().filter { !it.isDefault }

        data class Bound(val idx: Int, val lo: Double, val hi: Double)
        val bounds = active.map { g -> Bound(g.attributeIndex, g.lowerBound, g.upperBound) }

        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        for (row in data) {
            val xOk = bounds.all { b -> val v = row[b.idx]; !v.isNaN() && v >= b.lo && v <= b.hi }
            val yOk = (row[rIdx] >= rLo && row[rIdx] <= rUp)
            if (xOk) { if (yOk) tp++ else fp++ } else { if (yOk) fn++ else tn++ }
        }

        val denom1 = fp + tn
        val denom2 = fn + tp
        val type1 = if (denom1 > 0) fp.toDouble() / denom1 else 0.0
        val type2 = if (denom2 > 0) fn.toDouble() / denom2 else 0.0

        val score = when {
            type1 == 0.0 && type2 == 0.0 -> 0.0
            type1 == 0.0 || type2 == 0.0 -> Double.POSITIVE_INFINITY
            else -> abs((max(type1, type2) / min(type1, type2)) - 1.0)
        }

        tpList += tp; fpList += fp; tnList += tn; fnList += fn
        type1List += type1; type2List += type2
        ratioList += if (type2 > 0.0) type1 / type2 else Double.POSITIVE_INFINITY

        if (score < bestScore) { bestScore = score; bestIdx = i }
    }

    val metrics = mapOf(
        "TP" to tpList,
        "FP" to fpList,
        "TN" to tnList,
        "FN" to fnList,
        "type1" to type1List,
        "type2" to type2List,
        "ratio" to ratioList
    )

    return PFSeries(seriesName, front, metrics, if (bestIdx >= 0) bestIdx else null)
}


object FrontStore {
    private val dir = File(plots_file_path).apply { mkdirs() }

    private fun safeName(s: String) = s.replace(Regex("""[^\w\-.]+"""), "_").take(120)

    /** Saves plot as HTML and returns file:// URL, or null on failure. */
    fun saveAndUrl(plot: org.jetbrains.letsPlot.intern.Plot, titleHint: String): String? = try {
        val base = safeName(titleHint)
        val out = File(dir, "$base.html")
        ggsave(plot, path = out.parent, filename = out.name)
        out.toURI().toString()
    } catch (t: Throwable) {
        println("$YELLOW[⚠️ Failed to save front plot: ${t.message}]$RESET"); null
    }
}


// ---------------------- Pareto front (content-based equality) ----------------------

fun paretoFrontOf(
    population: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
): List<Phenotype<AttributeGene, Vec<DoubleArray>>> {
    val fits: ISeq<Vec<DoubleArray>> = population.map { it.fitness() }
    val frontFits: ISeq<Vec<DoubleArray>> = Pareto.front(fits)

    // Build a content-based key for Vec<DoubleArray> (avoids reference equality pitfalls)
    fun key(v: Vec<DoubleArray>): String = v.data().joinToString("\u0001") { it.toString() }
    val frontKeys = frontFits.asList().map(::key).toHashSet()

    return population.stream()
        .filter { pt -> key(pt.fitness()) in frontKeys }
        .toList()
}

// ---------------------- Plot: SupportX–Confidence combined view ----------------------
// Multi-line numeric LHS:
fun numericRuleString(
    header: List<String>,
    gt: Genotype<AttributeGene>
): String {
    val lhs = gt[0] as RuleSideChromosome
    val active = lhs.asSequence()
        .filterNotNull()
        .filter { !it.isDefault }
        .toList()

    if (active.isEmpty()) return "(no antecedent)"

    val body = active.joinToString("\n") { g ->
        val name = header.getOrNull(g.attributeIndex) ?: "attr_${g.attributeIndex}"
        val lb = String.format("%.3f", g.lowerBound)
        val ub = String.format("%.3f", g.upperBound)
        "• $name ∈ \n [$lb, $ub]"
    }

    return "Numeric rule:\n$body"
}

fun buildParetoFrontPlotCombined(
    seriesList: List<PFSeries>,
    dataset: DatasetWithHeader = datasetWithHeader,
    title: String = "Pareto Front (Gen ${hp.maxGenerations})",
    randomFront: Boolean = false
): org.jetbrains.letsPlot.intern.Plot {

    // Precompute once
    val sortedCols = computeSortedColumns(dataset.data) // -> List<DoubleArray>

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
        dataset: DatasetWithHeader,
        sortedCols: List<DoubleArray>
    ): List<SeriesPoint> {
        return s.front.mapIndexed { idx, pt ->
            val f = pt.fitness().data()

            val rawSupport = f[0]
            val x = rawSupport.toInt()

            val y = f[1]

            val rulePct = compactRuleString(dataset.header, sortedCols, pt.genotype())
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

    // Representative non-fixed attribute index for a phenotype's LHS (or null if none)
    fun representativeAttributeIndex(
        pt: Phenotype<AttributeGene, Vec<DoubleArray>>,
        fixedIndices: Set<Int>
    ): Int? {
        val lhs = pt.genotype()[0] as RuleSideChromosome
        return lhs.asSequence()
            .filter { !it.isDefault && it.attributeIndex !in fixedIndices }
            .map { it.attributeIndex }
            .minOrNull()
    }

    val palette = when (seriesList.size) {
        0 -> emptyList()
        1 -> listOf("#1f77b4")
        2 -> listOf("#1f77b4", "#ff7f0e")
        else -> (0 until seriesList.size).map { i ->
            "hsl(${(360.0 / seriesList.size * i).toInt()},70%,50%)"
        }
    }
    val breaks = seriesList.map { it.name }

    // Tooltip (Y is Confidence)
    val tt = layerTooltips()
        .title("@rulePct")
        .format("@ratio", ".3f")
        .line("@ruleNum")
        .line("TP=@TP  FP=@FP \n TN=@TN  FN=@FN")
        .line("Type1 vs Type2 error =@ratio")


    var plot = letsPlot() +
            ggtitle(title) +
            scaleColorManual(breaks = breaks, values = palette.take(breaks.size)) +
            guides(fill = "legend") +
            ggsize(1000, 650)

    // Parent front for fixed attribute detection (intersection across rules)
    val parentFront = seriesList.find { it.name.equals("Parent", ignoreCase = true) }?.front
    val fixedIndices: Set<Int> = run {
        val pf = parentFront
        if (pf == null || pf.isEmpty) emptySet()
        else {
            pf.flatMap { pt ->
                val lhs = pt.genotype()[0] as RuleSideChromosome
                lhs.asSequence()
                    .filter { it != null && !it.isDefault }
                    .map { it!!.attributeIndex }
                    .toList()
            }.toSet()   // union of all indices across all rules
        }
    }

    // ---------------- Between-front fill clipped to [xL, xR] with vertical edges ----------------

    val parentSeries = seriesList.find { it.name.equals("Parent", ignoreCase = true) }
    val childSeries  = seriesList.firstOrNull { !it.name.equals("Parent", ignoreCase = true) }
    if (parentSeries != null && childSeries != null) {
        val pPts = extractSortedPoints(parentSeries, dataset, sortedCols)
        val cPts = extractSortedPoints(childSeries,  dataset, sortedCols)

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
        val pts = extractSortedPoints(s, dataset, sortedCols)
        val n = pts.size
        if (n == 0) return@forEachIndexed

        val xs       = pts.map { it.x }
        val ys       = pts.map { it.y }
        val rulePcts = pts.map { it.rulePct }
        val ruleNums = pts.map { it.ruleNum }

        val metrics = s.metrics
        fun <T> getMetric(name: String, idx: Int): T? {
            val list = metrics?.get(name) as? List<*>
            @Suppress("UNCHECKED_CAST")
            return list?.getOrNull(idx) as? T
        }

        val tps    = pts.map { getMetric<Int>("TP",    it.idx) ?: 0 }
        val fps    = pts.map { getMetric<Int>("FP",    it.idx) ?: 0 }
        val tns    = pts.map { getMetric<Int>("TN",    it.idx) ?: 0 }
        val fns    = pts.map { getMetric<Int>("FN",    it.idx) ?: 0 }
        val ratios = pts.map { getMetric<Double>("ratio", it.idx) ?: Double.NaN }

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
        val isRandomFront = randomFront && s.name.contains("random", ignoreCase = true)
        if (isRandomFront) {
            val sortedPts = s.front.sortedBy { it.fitness().data()[0] }
            val attrLabels: List<String> = (0 until n).map { k ->
                val idxAttr = representativeAttributeIndex(sortedPts[k], fixedIndices)
                idxAttr?.let { dataset.header.getOrNull(it) ?: "attr#$it" } ?: "None"
            }
            val pointData = mapOf(
                "SupportX"   to xs,
                "Confidence" to ys,
                "rulePct"    to rulePcts,   // for title
                "ruleNum"    to ruleNums,   // for body
                "attr"       to attrLabels,
                "TP"         to tps,
                "FP"         to fps,
                "TN"         to tns,
                "FN"         to fns,
                "ratio"      to ratios
            )
            plot += geomPoint(
                data = pointData,
                size = 3.0,
                alpha = 0.95,
                shape = 21,
                stroke = 0.3,
                tooltips = tt
            ) { x = "SupportX"; y = "Confidence"; fill = "attr" }
        } else {
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
    }


    plot += xlab("SupportX") + ylab("Confidence")
    return plot
}


fun renderFrontPlotUrl(
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    childFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    title: String,
    randomFront: Boolean
): String? = try {
    val series = buildList {
        if (parentFront != null && !parentFront.isEmpty) add(toPFSeries(parentFront, "Parent"))
        add(toPFSeries(childFront, if (randomFront) "Random" else "Child"))
    }
    val plot = buildParetoFrontPlotCombined(series, title = title, randomFront = randomFront)
    val filename = title.substringBefore("Improvement").ifBlank { title }.trim()
    FrontStore.saveAndUrl(plot, filename).let { it?.substring(it.indexOf(plots_file_path)) }
} catch (t: Throwable) {
    println("$YELLOW[⚠️ Couldn’t render plot: ${t.message}]$RESET"); null
}
