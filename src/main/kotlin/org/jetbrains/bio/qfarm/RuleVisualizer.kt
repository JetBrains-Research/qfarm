package org.jetbrains.bio.qfarm

// ---------- Imports ----------
import org.jetbrains.letsPlot.*
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.scale.scaleFillManual
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.scale.guides
import org.jetbrains.letsPlot.tooltips.layerTooltips
import java.util.Locale

import java.awt.Desktop
import java.nio.file.Paths
import java.util.UUID

private fun inRange(v: Double, r: ClosedFloatingPointRange<Double>) =
    !v.isNaN() && v >= r.start && v <= r.endInclusive

private fun caseLabel(lhsOk: Boolean, rhsOk: Boolean): String = when {
    lhsOk && rhsOk -> "TP"
    lhsOk && !rhsOk -> "FP"
    !lhsOk && rhsOk -> "FN"
    else -> "TN"
}

private fun resolveCol(header: List<String>, wanted: String): String {
    if (wanted in header) return wanted
    header.firstOrNull { it.startsWith("$wanted.") }?.let { return it }
    header.firstOrNull { it.substringBefore('.') == wanted }?.let { return it }
    error("Column '$wanted' not found. Available: ${header.joinToString()}")
}

private fun openPlotInBrowser(plot: Plot, suggestedName: String = "plot") {
    val tmpDir = System.getProperty("java.io.tmpdir")
    val safe = suggestedName.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    val fileName = "${safe}_${UUID.randomUUID().toString().take(8)}.html"
    val outPath = ggsave(plot, filename = fileName, path = tmpDir)
    val f = Paths.get(outPath).toFile()
    f.deleteOnExit()
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(f.toURI())
    else println("↗ Open manually: file://${f.absolutePath}")
}

// ---------------- 1) 1D visualizer (returns one Plot) ----------------
fun visualizeRuleWithDataset(
    dataset: DatasetWithHeader,
    lhsRanges: Map<String, ClosedFloatingPointRange<Double>>,   // 1 key
    rhsAttr: String,
    rhsRange: ClosedFloatingPointRange<Double>
): Plot {
    val header = dataset.header
    val data   = dataset.data

    val keysAsStrings = lhsRanges.keys.map { it.toString() }

    val lhsKeys: List<String> = when (lhsRanges) {
        is LinkedHashMap<*, *> ->
            keysAsStrings

        else ->
            header.filter { it in keysAsStrings }
                .ifEmpty { keysAsStrings }
    }
    require(lhsKeys.size == 1) { "visualizeRuleWithDataset() expects exactly 1 LHS attribute. For 2 LHS, call visualizeRuleWithDataset2DAsTwoPlots()." }

    val aKey = lhsKeys[0]
    val aCol = resolveCol(header, aKey)
    val aIdx = header.indexOf(aCol)
    val aRange = lhsRanges.getValue(aKey)
    val aVals = data.map { it[aIdx] }

    val rhsCol = resolveCol(header, rhsAttr)
    val rhsIdx = header.indexOf(rhsCol)
    val rhsVals = data.map { it[rhsIdx] }

    // Percentiles (using your helpers)
    val sortedColumns = computeSortedColumns(dataset.data)
    fun pct(idx: Int, v: Double) = cumulativePercentage(sortedColumns[idx], v).toInt()
    val aLoPct = pct(aIdx, aRange.start); val aHiPct = pct(aIdx, aRange.endInclusive)
    val rLoPct = pct(rhsIdx, rhsRange.start); val rHiPct = pct(rhsIdx, rhsRange.endInclusive)

    // Case labels w.r.t full (1D) rule
    val labels = data.map { row ->
        val lhsOk = inRange(row[aIdx], aRange)
        val rhsOk = inRange(row[rhsIdx], rhsRange)
        caseLabel(lhsOk, rhsOk)
    }
    val counts = labels.groupingBy { it }.eachCount().withDefault { 0 }

    val X = "LHS"; val Y = "RHS"
    val df = mutableMapOf<String, Any>(X to aVals, Y to rhsVals, "case" to labels)

    val minX = aVals.filter { !it.isNaN() }.minOrNull() ?: 0.0
    val maxX = aVals.filter { !it.isNaN() }.maxOrNull() ?: 1.0
    val minY = rhsVals.filter { !it.isNaN() }.minOrNull() ?: 0.0
    val maxY = rhsVals.filter { !it.isNaN() }.maxOrNull() ?: 1.0

    val vBand = mapOf("xmin" to listOf(aRange.start), "xmax" to listOf(aRange.endInclusive),
        "ymin" to listOf(minY), "ymax" to listOf(maxY))
    val hBand = mapOf("xmin" to listOf(minX), "xmax" to listOf(maxX),
        "ymin" to listOf(rhsRange.start), "ymax" to listOf(rhsRange.endInclusive))
    val interBand = mapOf("xmin" to listOf(aRange.start), "xmax" to listOf(aRange.endInclusive),
        "ymin" to listOf(rhsRange.start), "ymax" to listOf(rhsRange.endInclusive))

    val tt = layerTooltips()
        .format("@$X", ".4f").format("@$Y", ".4f")
        .line("$aCol=@$X")
        .line("$rhsCol=@$Y")

    val titleCounts = "TP=${counts.getValue("TP")}, FP=${counts.getValue("FP")}, TN=${counts.getValue("TN")}, FN=${counts.getValue("FN")}"
    val tpC = counts.getValue("TP"); val fpC = counts.getValue("FP")
    val tnC = counts.getValue("TN"); val fnC = counts.getValue("FN")
    val n = tpC + fpC + tnC + fnC

    val supportX = if (n > 0) (tpC + fpC) else 0                      // P(X=1)
    val pY       = if (n > 0) (tpC + fnC).toDouble() / n else 0.0                       // P(Y=1)
    val conf     = if ((tpC + fpC) > 0) tpC.toDouble() / (tpC + fpC) else 0.0           // P(Y=1|X=1)
    val lift     = if (pY > 0.0) conf / pY else Double.NaN

    val liftSupportLine = "SupportX=$supportX / $n,  Lift=${"%.4f".format(Locale.US, lift)}"

    val numericRuleLine     = "$aCol∈[${aRange.start}, ${aRange.endInclusive}] ⇒ $rhsCol∈[${rhsRange.start}, ${rhsRange.endInclusive}]"
    val percentileRuleLine  = "${aCol}∈[${aLoPct}..${aHiPct}]% ⇒ ${rhsCol}∈[${rLoPct}..${rHiPct}]%"
    val commonTitle         = "$numericRuleLine\n$percentileRuleLine\n$titleCounts\n$liftSupportLine"

            return letsPlot(df) { x = X; y = Y; fill = "case" } +
            ggtitle(commonTitle) +
            xlab(aKey) + ylab(rhsAttr) +
            geomRect(data = vBand, alpha = 0.08, fill = "#42A5F5") +
            geomRect(data = hBand, alpha = 0.08, fill = "#66BB6A") +
            geomRect(data = interBand, alpha = 0.12, fill = "#FFC107") +
            geomPoint(size = 1.8, alpha = 0.95, shape = 21, stroke = 0.3, color = "#333333", tooltips = tt) +
            scaleFillManual(
                name = "Case",
                breaks = listOf("TP", "FP", "FN", "TN"),
                values = listOf("#2E7D32", "#D32F2F", "#FFB74D", "#BDBDBD")  // FN light orange
            ) +
            guides(fill = "legend") +
            ggsize(950, 700)
}

// 2-LHS: build THREE plots (LHS1 vs RHS, LHS2 vs RHS, LHS1 vs LHS2)
fun visualizeRuleWithDataset2DPlots(
    dataset: DatasetWithHeader,
    lhsRanges: Map<String, ClosedFloatingPointRange<Double>>,   // exactly 2 keys
    rhsAttr: String,
    rhsRange: ClosedFloatingPointRange<Double>
): Triple<Plot, Plot, Plot> {
    val header = dataset.header
    val data   = dataset.data

    val keys = lhsRanges.keys.map { it.toString() }

    val lhsKeys = header.filter(keys::contains)
        .ifEmpty { keys }
    require(lhsKeys.size == 2) { "Expected exactly 2 LHS attributes." }

    val aKey = lhsKeys[0]; val bKey = lhsKeys[1]
    val aCol = resolveCol(header, aKey); val bCol = resolveCol(header, bKey)
    val aIdx = header.indexOf(aCol);     val bIdx = header.indexOf(bCol)
    val aRange = lhsRanges.getValue(aKey); val bRange = lhsRanges.getValue(bKey)
    val aVals = data.map { it[aIdx] };     val bVals = data.map { it[bIdx] }

    val rhsCol = resolveCol(header, rhsAttr)
    val rhsIdx = header.indexOf(rhsCol)
    val rhsVals = data.map { it[rhsIdx] }

    // Percentiles using your helpers
    val sortedColumns = computeSortedColumns(dataset.data)
    fun pct(idx: Int, v: Double) = cumulativePercentage(sortedColumns[idx], v).toInt()
    val aLoPct = pct(aIdx, aRange.start); val aHiPct = pct(aIdx, aRange.endInclusive)
    val bLoPct = pct(bIdx, bRange.start); val bHiPct = pct(bIdx, bRange.endInclusive)
    val rLoPct = pct(rhsIdx, rhsRange.start); val rHiPct = pct(rhsIdx, rhsRange.endInclusive)

    // Label each row by the FULL rule (A∈range && B∈range) vs RHS window
    val labels = data.map { row ->
        val lhsOk = inRange(row[aIdx], aRange) && inRange(row[bIdx], bRange)
        val rhsOk = inRange(row[rhsIdx], rhsRange)
        caseLabel(lhsOk, rhsOk)
    }
    val counts = labels.groupingBy { it }.eachCount().withDefault { 0 }
    val titleCounts = "TP=${counts.getValue("TP")}, FP=${counts.getValue("FP")}, TN=${counts.getValue("TN")}, FN=${counts.getValue("FN")}"
    val tpC = counts.getValue("TP"); val fpC = counts.getValue("FP")
    val tnC = counts.getValue("TN"); val fnC = counts.getValue("FN")
    val n = tpC + fpC + tnC + fnC

    val supportX = if (n > 0) (tpC + fpC) else 0
    val pY       = if (n > 0) (tpC + fnC).toDouble() / n else 0.0
    val conf     = if ((tpC + fpC) > 0) tpC.toDouble() / (tpC + fpC) else 0.0
    val lift     = if (pY > 0.0) conf / pY else Double.NaN

    val liftSupportLine2D   = "SupportX=$supportX / $n;  Lift=${"%.4f".format(Locale.US, lift)}"

    val numericRuleLine2D    = "$aCol∈[${aRange.start}, ${aRange.endInclusive}] + $bCol∈[${bRange.start}, ${bRange.endInclusive}] ⇒ $rhsCol∈[${rhsRange.start}, ${rhsRange.endInclusive}]"
    val percentileRuleLine2D = "${aCol}∈[${aLoPct}..${aHiPct}]% + ${bCol}∈[${bLoPct}..${bHiPct}]% ⇒ ${rhsCol}∈[${rLoPct}..${rHiPct}]%"
    val commonTitle2D        = "$numericRuleLine2D\n$percentileRuleLine2D\n$titleCounts\n$liftSupportLine2D"


    fun lhsVsRhsPlot(
        lhsKey: String, lhsCol: String, lhsIdx: Int,
        lhsRange: ClosedFloatingPointRange<Double>, lhsVals: List<Double>,
        titlePrefix: String,
        otherCol: String, otherVals: List<Double>              // <-- ADDED
    ): Plot {
        val X = "LHS"; val Y = "RHS"; val Z = "LHS_other"          // <-- ADDED

        val df = mutableMapOf<String, Any>(
            X to lhsVals,
            Y to rhsVals,
            Z to otherVals,                                         // <-- ADDED
            "case" to labels
        )

        val minX = lhsVals.filter { !it.isNaN() }.minOrNull() ?: 0.0
        val maxX = lhsVals.filter { !it.isNaN() }.maxOrNull() ?: 1.0
        val minY = rhsVals.filter { !it.isNaN() }.minOrNull() ?: 0.0
        val maxY = rhsVals.filter { !it.isNaN() }.maxOrNull() ?: 1.0

        val vBand = mapOf("xmin" to listOf(lhsRange.start), "xmax" to listOf(lhsRange.endInclusive),
            "ymin" to listOf(minY), "ymax" to listOf(maxY))
        val hBand = mapOf("xmin" to listOf(minX), "xmax" to listOf(maxX),
            "ymin" to listOf(rhsRange.start), "ymax" to listOf(rhsRange.endInclusive))
        val interBand = mapOf("xmin" to listOf(lhsRange.start), "xmax" to listOf(lhsRange.endInclusive),
            "ymin" to listOf(rhsRange.start), "ymax" to listOf(rhsRange.endInclusive))

        val tt = layerTooltips()
            .format("@$X", ".4f").format("@$Y", ".4f")
            .format("@$Z", ".4f")                                   // <-- ADDED
            .line("$lhsCol=@$X")
            .line("$rhsCol=@$Y")
            .line("$otherCol=@$Z")

        return letsPlot(df) { x = X; y = Y; fill = "case" } +
                ggtitle(commonTitle2D) +
                xlab(lhsKey) + ylab(rhsAttr) +
                geomRect(data = vBand, alpha = 0.08, fill = "#42A5F5") +
                geomRect(data = hBand, alpha = 0.08, fill = "#66BB6A") +
                geomRect(data = interBand, alpha = 0.12, fill = "#FFC107") +
                geomPoint(size = 1.8, alpha = 0.95, shape = 21, stroke = 0.3, color = "#333333", tooltips = tt) +
                scaleFillManual(
                    name = "Case",
                    breaks = listOf("TP", "FP", "FN", "TN"),
                    values = listOf("#2E7D32", "#D32F2F", "#FFB74D", "#BDBDBD")
                ) +
                guides(fill = "legend") +
                ggsize(950, 700)
    }

    fun lhs1VsLhs2Plot(): Plot {
        val X = "LHS1"; val Y = "LHS2"; val R = "RHS"
        val df = mutableMapOf<String, Any>(
            X to aVals,
            Y to bVals,
            R to rhsVals,                                           // <-- ADDED
            "case" to labels
        )

        val rect = mapOf(
            "xmin" to listOf(aRange.start), "xmax" to listOf(aRange.endInclusive),
            "ymin" to listOf(bRange.start), "ymax" to listOf(bRange.endInclusive)
        )

        val tt = layerTooltips()
            .format("@$X", ".4f").format("@$Y", ".4f").format("@$R", ".4f")   // <-- ADDED
            .line("$aCol=@$X")
            .line("$bCol=@$Y")
            .line("$rhsCol=@$R")

        return letsPlot(df) { x = X; y = Y; fill = "case" } +
                ggtitle(commonTitle2D) +
                xlab(aKey) + ylab(bKey) +
                geomRect(data = rect, alpha = 0.10, fill = "#FFC107") +   // rule rectangle
                geomPoint(size = 1.8, alpha = 0.95, shape = 21, stroke = 0.3, color = "#333333", tooltips = tt) +
                scaleFillManual(
                    name = "Case",
                    breaks = listOf("TP", "FP", "FN", "TN"),
                    values = listOf("#2E7D32", "#D32F2F", "#FFB74D", "#BDBDBD")
                ) +
                guides(fill = "legend") +
                ggsize(950, 700)
    }

    val plotA = lhsVsRhsPlot(
        aKey, aCol, aIdx, aRange, aVals,
        titlePrefix = "LHS1 vs RHS",
        otherCol = bCol, otherVals = bVals                      // <-- ADDED
    )

    val plotB = lhsVsRhsPlot(
        bKey, bCol, bIdx, bRange, bVals,
        titlePrefix = "LHS2 vs RHS",
        otherCol = aCol, otherVals = aVals                      // <-- ADDED
    )
    val plotC = lhs1VsLhs2Plot()
    return Triple(plotA, plotB, plotC)
}


// One wrapper for both 1D and 2D: opens plots in browser automatically and returns them.
fun visualizeRuleWithDatasetOpenAuto(
    dataset: DatasetWithHeader,
    lhsRanges: Map<String, ClosedFloatingPointRange<Double>>,
    rhsAttr: String,
    rhsRange: ClosedFloatingPointRange<Double>,
    baseName: String = "rule_vis"
): List<Plot> {
    val lhsCount = lhsRanges.size
    val keySlug = lhsRanges.keys.joinToString("_").replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return when (lhsCount) {
        1 -> {
            val p = visualizeRuleWithDataset(dataset, lhsRanges, rhsAttr, rhsRange)
            openPlotInBrowser(p, "${baseName}_${keySlug}_${rhsAttr}_1D")
            listOf(p)
        }
        2 -> {
            val (p1, p2, p3) = visualizeRuleWithDataset2DPlots(dataset, lhsRanges, rhsAttr, rhsRange)
            val base = "${baseName}_${keySlug}_${rhsAttr}_2D"
            openPlotInBrowser(p1, base + "_lhs1_rhs")
            openPlotInBrowser(p2, base + "_lhs2_rhs")
            openPlotInBrowser(p3, base + "_lhs1_lhs2")
            listOf(p1, p2, p3)
        }
        else -> error("Expected 1 or 2 LHS attributes; got $lhsCount")
    }
}
