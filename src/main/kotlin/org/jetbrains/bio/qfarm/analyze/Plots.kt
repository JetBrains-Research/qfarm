package org.jetbrains.bio.qfarm.analyze

import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomSegment
import org.jetbrains.letsPlot.geom.geomText
import org.jetbrains.letsPlot.geom.geomTile
import org.jetbrains.letsPlot.gggrid
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.scale.scaleFillGradient
import org.jetbrains.letsPlot.scale.scaleFillGradient2
import org.jetbrains.letsPlot.scale.scaleXContinuous
import org.jetbrains.letsPlot.scale.scaleYContinuous
import org.jetbrains.letsPlot.themes.elementBlank
import org.jetbrains.letsPlot.themes.elementText
import org.jetbrains.letsPlot.themes.theme
import java.io.File

private const val VIRIDIS_LOW = "#440154"
private const val VIRIDIS_HIGH = "#fde725"

/** Saves [fig] as <stem>.png and <stem>.svg. */
private fun save(fig: Figure, stem: File) {
    stem.parentFile.mkdirs()
    for (ext in listOf("png", "svg")) {
        runCatching { ggsave(fig, "${stem.name}.$ext", path = stem.parent) }
            .onFailure { println("WARNING: could not write ${stem.name}.$ext: ${it.message}") }
    }
}

/**
 * Heatmap with row/column labels; cells with NaN are left empty.
 * Row 0 is drawn at the top. [text] gives the cell annotation (null = none).
 */
private fun heatmap(
    m: Array<DoubleArray>, rowLabels: List<String>, colLabels: List<String>, title: String,
    fillScale: org.jetbrains.letsPlot.intern.Scale, text: ((Int, Int) -> String?)? = null,
    showRowLabels: Boolean = true, rotateX: Boolean = true, darkCell: (Double) -> Boolean = { false }
): Plot {
    val xs = mutableListOf<Int>()
    val ys = mutableListOf<Int>()
    val vs = mutableListOf<Double>()
    val ls = mutableListOf<String>()
    for (i in m.indices) for (j in m[i].indices) if (!m[i][j].isNaN()) {
        xs += j
        ys += m.size - 1 - i
        vs += m[i][j]
        ls += text?.invoke(i, j) ?: ""
    }
    val data = mapOf("x" to xs, "y" to ys, "v" to vs, "label" to ls)
    var p = letsPlot(data) { x = "x"; y = "y"; fill = "v" } +
            geomTile() + fillScale +
            scaleXContinuous(breaks = colLabels.indices.toList(), labels = colLabels, expand = listOf(0, 0)) +
            scaleYContinuous(
                breaks = rowLabels.indices.toList(), labels = rowLabels.indices.map { rowLabels[m.size - 1 - it] },
                expand = listOf(0, 0)
            ) +
            ggtitle(title) + xlab("") + ylab("") +
            theme(
                axisTextX = if (rotateX) elementText(angle = 90, size = 7) else elementText(size = 7),
                axisTextY = if (showRowLabels) elementText(size = 7) else elementBlank(),
                panelGrid = elementBlank()
            )
    if (text != null) {
        // white annotations on dark cells, black elsewhere
        for (dark in listOf(false, true)) {
            val k = vs.indices.filter { darkCell(vs[it]) == dark }
            if (k.isEmpty()) continue      // lets-plot cannot render a layer with empty data
            p += geomText(data = mapOf("x" to k.map { xs[it] }, "y" to k.map { ys[it] }, "label" to k.map { ls[it] }),
                size = 2.5, color = if (dark) "white" else "black", inheritAes = false) { x = "x"; y = "y"; label = "label" }
        }
    }
    return p
}

/** null_calibration.py heatmaps: metric per class and the positive - negative difference. */
fun nullHeatmaps(
    rows: List<Map<String, Any?>>, keys: List<String>, metric: String, fdr: Double, leavesOnly: Boolean, stem: File
) {
    val pos = keys.withIndex().associate { (i, k) -> k to i }
    val n = keys.size
    val v = CLASSES.associate { (c, _) -> c to Array(n) { DoubleArray(n) { Double.NaN } } }
    val sig = CLASSES.associate { (c, _) -> c to Array(n) { BooleanArray(n) } }
    for (r in rows) {
        val i = pos.getValue(r["node_a"] as String)
        val j = pos.getValue(r["node_b"] as String)
        val c = r["cls"] as String
        val obs = (r["${metric}_obs"] as? Double) ?: Double.NaN
        val s = ((r["${metric}_q"] as? Double) ?: 1.0) < fdr
        v.getValue(c)[i][j] = obs; v.getValue(c)[j][i] = obs
        sig.getValue(c)[i][j] = s; sig.getValue(c)[j][i] = s
    }
    val p = v.getValue("positive")
    val q = v.getValue("negative")
    val diff = Array(n) { i -> DoubleArray(n) { j -> p[i][j] - q[i][j] } }
    fun div(low: String, high: String) =
        scaleFillGradient2(low = low, mid = "#f7f7f7", high = high, midpoint = 0.0, name = "", limits = -1 to 1)
    fun note(m: Array<DoubleArray>, s: Array<BooleanArray>?) = { i: Int, j: Int ->
        if (i == j) null else "%.2f%s".format(m[i][j], if (s == null || s[i][j]) "" else "·")
    }
    val panels = listOf(
        heatmap(p, keys, keys, "positive: $metric", div("#2166ac", "#b2182b"), note(p, sig.getValue("positive"))),
        heatmap(q, keys, keys, "negative: $metric", div("#2166ac", "#b2182b"), note(q, sig.getValue("negative")), showRowLabels = false),
        heatmap(diff, keys, keys, "positive - negative", div("#c51b7d", "#4d9221"), note(diff, null), showRowLabels = false)
    )
    val side = 250 + 28 * n
    val title = "Pairwise $metric, ${if (leavesOnly) "leaf nodes" else "all nodes"} (· = not significant after FDR)"
    save(gggrid(panels.mapIndexed { k, pl -> if (k == 0) pl + ggtitle("$title\npositive: $metric") else pl }, ncol = 3) +
            ggsize(3 * side + 200, side + 150), stem)
}

/** cluster_donors.py figure: per node (rows) and cluster (columns), mean standardised score and share flagged. */
fun donorProfilePlot(
    names: List<String>, order: IntArray, colLabels: List<String>,
    meanZ: Array<DoubleArray>, share: Array<DoubleArray>,
    leftTitle: String, rightTitle: String, title: String, stem: File
) {
    val rowNames = order.map { names[it] }
    val m = Array(order.size) { meanZ[order[it]] }
    val s = Array(order.size) { share[order[it]] }
    val lim = maxOf(0.5, m.maxOf { r -> r.maxOf { kotlin.math.abs(it) } })
    val annotate = names.size <= 60
    val left = heatmap(
        m, rowNames, colLabels, "$title\n$leftTitle",
        scaleFillGradient2(low = "#2166ac", mid = "#f7f7f7", high = "#b2182b", midpoint = 0.0, name = "", limits = -lim to lim),
        if (annotate) { i, j -> "%.2f".format(m[i][j]) } else null, rotateX = false,
        darkCell = { kotlin.math.abs(it) > 0.6 * lim }
    )
    val right = heatmap(
        s, rowNames, colLabels, rightTitle,
        scaleFillGradient(low = VIRIDIS_LOW, high = VIRIDIS_HIGH, name = "", limits = 0 to 1),
        if (annotate) { i, j -> "%.2f".format(s[i][j]) } else null, showRowLabels = false, rotateX = false,
        darkCell = { it < 0.5 }
    )
    // wide enough for the panel titles even with few clusters
    val titleWidth = 2 * 11 * maxOf(leftTitle.length, rightTitle.length, title.length)
    val w = maxOf(200 + 7 * (names.maxOfOrNull { it.length } ?: 10) + 160 * colLabels.size, titleWidth)
    save(gggrid(listOf(left, right), ncol = 2) + ggsize(w, 250 + 22 * names.size), stem)
}

/** cluster_nodes.py figure: dendrogram (left) and bootstrap co-clustering frequency (right). */
fun nodeDendrogramPlot(
    names: List<String>, tree: Linkage, labels: IntArray, co: Array<DoubleArray>,
    distLabel: String, title: String, stem: File
) {
    val order = tree.leaves()
    val segs = tree.dendrogramSegments()
    val n = names.size
    val tick = order.indices.map { 5.0 + 10.0 * it }
    val dendro = letsPlot(
        mapOf(
            "x" to segs.map { it[0] }, "y" to segs.map { it[1] },
            "xend" to segs.map { it[2] }, "yend" to segs.map { it[3] }
        )
    ) { x = "x"; y = "y"; xend = "xend"; yend = "yend" } +
            geomSegment(color = "#1f77b4") +
            scaleYContinuous(
                breaks = tick, labels = order.mapIndexed { p, i -> "${p + 1}. ${names[i]}  [c${labels[i]}]" },
                limits = 0 to 10 * n
            ) +
            ggtitle(title) + xlab(distLabel) + ylab("") +
            theme(axisTextY = elementText(size = 8), panelGrid = elementBlank())

    // leaf 0 is drawn at the bottom (as in scipy), so reverse rows for the top-down heatmap
    val ordered = Array(n) { i -> DoubleArray(n) { j -> co[order[n - 1 - i]][order[j]] } }
    val heat = heatmap(
        ordered, (n downTo 1).map { it.toString() }, (1..n).map { it.toString() }, "bootstrap co-clustering frequency",
        scaleFillGradient(low = VIRIDIS_LOW, high = VIRIDIS_HIGH, name = "", limits = 0 to 1),
        showRowLabels = false, rotateX = false
    )
    val w = 900 + 7 * (names.maxOfOrNull { it.length } ?: 10)
    save(gggrid(listOf(dendro, heat), ncol = 2, widths = listOf(1.3, 1.0)) + ggsize(w, 250 + 28 * n), stem)
}
