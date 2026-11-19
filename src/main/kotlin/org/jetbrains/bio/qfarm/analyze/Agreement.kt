package org.jetbrains.bio.qfarm.analyze

import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Class names used in all outputs: donors inside (positive) or outside (negative) the RHS range. */
val CLASSES = listOf("positive" to true, "negative" to false)

/** Flag threshold of a node: a fixed score, or the score flagging its top fraction of all donors (ties included). Score 0 is never flagged. */
fun flagThreshold(s: IntArray, scoreMin: Int, topFrac: Double?): Int {
    if (topFrac == null) return max(scoreMin, 1)
    val desc = s.sortedDescending()
    return max(desc[max(ceil(topFrac * s.size).toInt() - 1, 0)], 1)
}

fun relation(a: TreeNode, b: TreeNode): String = when {
    b.parentKey == a.key || a.parentKey == b.key -> "parent_child"
    a.parentKey == b.parentKey -> "siblings"
    a.attributes.size != b.attributes.size &&
            (b.attributes.take(a.attributes.size) == a.attributes ||
                    a.attributes.take(b.attributes.size) == b.attributes) -> "ancestor"
    else -> "other"
}

/** Scores of one class, with ranks cached for the rank correlations. */
private class ClassView(sc: NodeScores, rows: IntArray) {
    val values = sc.scores.map { s -> IntArray(rows.size) { s[rows[it]] } }
    val ranks = values.map { v -> rankData(DoubleArray(v.size) { v[it].toDouble() }) }
    val constant = values.map { v -> v.all { it == v[0] } }
}

private fun binaryMetrics(fa: BooleanArray, fb: BooleanArray, positive: Boolean): Map<String, Any?> {
    val n = fa.size
    var a = 0
    var b = 0
    var c = 0
    var d = 0
    for (i in 0 until n) when {
        fa[i] && fb[i] -> a++
        fa[i] -> b++
        fb[i] -> c++
        else -> d++
    }
    val pa = (a + b).toDouble() / n
    val pb = (a + c).toDouble() / n
    val pe = pa * pb + (1 - pa) * (1 - pb)
    fun ratio(num: Double, den: Double) = if (den != 0.0) num / den else Double.NaN
    return linkedMapOf(
        "both" to a, "only_a" to b, "only_b" to c, "neither" to d,
        "flag_rate_a" to pa, "flag_rate_b" to pb,
        "jaccard" to ratio(a.toDouble(), (a + b + c).toDouble()),
        "pos_specific_agreement" to ratio(2.0 * a, (2 * a + b + c).toDouble()),
        "neg_specific_agreement" to ratio(2.0 * d, (2 * d + b + c).toDouble()),
        "disagreement" to (b + c).toDouble() / n,
        "kappa" to if (pe < 1) ((a + d).toDouble() / n - pe) / (1 - pe) else Double.NaN,
        "q_stat" to ratio(a.toDouble() * d - b.toDouble() * c, a.toDouble() * d + b.toDouble() * c),
        "double_fault" to (if (positive) d else a).toDouble() / n,
        "mcnemar_p" to binomTestHalf(minOf(b, c), b + c)
    )
}

/** pairwise_agreement.py: all node pairs, per class, at "score >= min" and (optionally) "top fraction". */
fun pairwiseAgreement(sc: NodeScores, scoreMin: Int, topFrac: Double?, out: File) {
    val views = CLASSES.associate { (cls, pos) -> cls to ClassView(sc, sc.indicesOf(pos)) }
    val ops = listOf("score>=$scoreMin" to null) + (topFrac?.let { listOf("top${fmtPct(it)}" to it) } ?: emptyList())
    val rows = mutableListOf<Map<String, Any?>>()
    val nodes = sc.nodes
    for ((opName, frac) in ops) {
        val thr = sc.scores.map { flagThreshold(it, scoreMin, frac) }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val (na, nb) = nodes[i] to nodes[j]
            val sa = na.attributes.toSet()
            val sb = nb.attributes.toSet()
            for ((cls, pos) in CLASSES) {
                val v = views.getValue(cls)
                val xa = v.values[i]
                val xb = v.values[j]
                val row = linkedMapOf<String, Any?>(
                    "node_a" to na.key, "node_b" to nb.key, "relation" to relation(na, nb),
                    "attr_jaccard" to (sa intersect sb).size.toDouble() / (sa union sb).size,
                    "operating_point" to opName, "cls" to cls, "n" to xa.size
                )
                row += binaryMetrics(BooleanArray(xa.size) { xa[it] >= thr[i] }, BooleanArray(xb.size) { xb[it] >= thr[j] }, pos)
                val varied = !v.constant[i] && !v.constant[j]
                row["kendall_tau_b"] = if (varied) kendallTauB(xa, xb) else Double.NaN
                row["spearman"] = if (varied) spearmanFromRanks(v.ranks[i], v.ranks[j]) else Double.NaN
                rows += row
            }
        }
    }
    writeCsv(File(out, "pairwise_agreement.csv"), rows)
    println("pairwise agreement: ${rows.size} rows")
    printMeans(rows, listOf("jaccard", "kappa", "kendall_tau_b"))
}

fun fmtPct(frac: Double): String {
    val p = frac * 100
    return if (p == Math.rint(p)) p.toLong().toString() else p.toString()
}

private fun printMeans(rows: List<Map<String, Any?>>, cols: List<String>, prefix: String = "") {
    println("%-10s %-14s".format("cls", "relation") + cols.joinToString("") { "%16s".format(prefix + it) })
    rows.groupBy { it["cls"] to it["relation"] }.toSortedMap(compareBy({ it.first as String }, { it.second as String }))
        .forEach { (k, g) ->
            val means = cols.map { c ->
                g.mapNotNull { r -> (r[prefix + c] as? Double)?.takeIf { !it.isNaN() } }.average()
            }
            println("%-10s %-14s".format(k.first, k.second) + means.joinToString("") { "%16.3f".format(it) })
        }
}

private class NullStat(val obs: Double, val mean: Double, val sd: Double, val p: Double)

private fun binaryNull(fa: BooleanArray, fb: BooleanArray): Map<String, NullStat> {
    val n = fa.size
    val ka = fa.count { it }
    val kb = fb.count { it }
    val aObs = fa.indices.count { fa[it] && fb[it] }
    val dist = hypergeom(n, ka, kb)
    val support = (max(0, ka + kb - n)..minOf(ka, kb)).toList()
    val w = support.map { dist.probability(it) }
    val p = dist.upperCumulativeProbability(aObs)
    val nn = n.toDouble()
    val pe = (ka.toDouble() * kb + (nn - ka) * (nn - kb)) / (nn * nn)
    val metrics = mapOf<String, (Int) -> Double>(
        "jaccard" to { a -> a / max(ka + kb - a, 1).toDouble() },
        "kappa" to { a -> if (pe < 1) ((nn - ka - kb + 2.0 * a) / nn - pe) / (1 - pe) else Double.NaN }
    )
    return metrics.mapValues { (_, f) ->
        val v = support.map(f)
        val ok = v.indices.filter { !v[it].isNaN() }
        val ws = ok.sumOf { w[it] }
        val m = if (ok.isEmpty()) Double.NaN else ok.sumOf { w[it] * v[it] } / ws
        val sd = if (ok.isEmpty()) Double.NaN else sqrt(ok.sumOf { w[it] * (v[it] - m) * (v[it] - m) } / ws)
        NullStat(f(aObs), m, sd, p)
    }
}

private fun tieCounts(x: IntArray) = x.toList().groupingBy { it }.eachCount().values.map { it.toDouble() }

/** Spearman (SD 1/sqrt(n-1)) and tie-corrected Kendall tau-b nulls under random pairing. */
private fun analyticNull(xa: IntArray, xb: IntArray, ra: DoubleArray, rb: DoubleArray, varied: Boolean): Map<String, NullStat> {
    val n = xa.size.toDouble()
    if (!varied || xa.size < 4) return emptyMap()
    val sObs = spearmanFromRanks(ra, rb)
    val sSd = 1 / sqrt(n - 1)
    val t = tieCounts(xa)
    val u = tieCounts(xb)
    val n0 = n * (n - 1) / 2
    val v0 = n * (n - 1) * (2 * n + 5)
    val varS = (v0 - t.sumOf { it * (it - 1) * (2 * it + 5) } - u.sumOf { it * (it - 1) * (2 * it + 5) }) / 18 +
            t.sumOf { it * (it - 1) } * u.sumOf { it * (it - 1) } / (2 * n * (n - 1)) +
            t.sumOf { it * (it - 1) * (it - 2) } * u.sumOf { it * (it - 1) * (it - 2) } / (9 * n * (n - 1) * (n - 2))
    val kSd = sqrt(varS) / sqrt((n0 - t.sumOf { it * (it - 1) / 2 }) * (n0 - u.sumOf { it * (it - 1) / 2 }))
    val kObs = kendallTauB(xa, xb)
    return linkedMapOf(
        "spearman" to NullStat(sObs, 0.0, sSd, normalSf(sObs / sSd)),
        "kendall_tau_b" to NullStat(kObs, 0.0, kSd, normalSf(kObs / kSd))
    )
}

val NULL_METRICS = listOf("jaccard", "kappa", "spearman", "kendall_tau_b")

/** null_calibration.py: observed agreement against its independence null, BH per class, heatmaps. */
fun nullCalibration(
    sc: NodeScores, scoreMin: Int, topFrac: Double?, fdr: Double,
    leavesOnly: Boolean, heatmapMetric: String, out: File
) {
    var idx = sc.nodes.indices.toList()
    if (leavesOnly) {
        val parents = sc.nodes.mapNotNull { it.parentKey }.toSet()
        idx = idx.filter { sc.nodes[it].key !in parents }
        println("leaf nodes (${idx.size}): ${idx.joinToString(", ") { sc.nodes[it].key }}")
    }
    val suffix = if (leavesOnly) "_leaves" else ""
    val thr = sc.scores.map { flagThreshold(it, scoreMin, topFrac) }
    val views = CLASSES.associate { (cls, pos) -> cls to ClassView(sc, sc.indicesOf(pos)) }

    writeCsv(File(out, "flag_rates$suffix.csv"), idx.map { j ->
        linkedMapOf<String, Any?>("node_key" to sc.nodes[j].key) + CLASSES.associate { (cls, _) ->
            val v = views.getValue(cls).values[j]
            cls to v.count { it >= thr[j] }.toDouble() / v.size
        }
    })

    val rows = mutableListOf<MutableMap<String, Any?>>()
    for (ii in idx.indices) for (jj in ii + 1 until idx.size) {
        val i = idx[ii]
        val j = idx[jj]
        for ((cls, _) in CLASSES) {
            val v = views.getValue(cls)
            val xa = v.values[i]
            val xb = v.values[j]
            val fa = BooleanArray(xa.size) { xa[it] >= thr[i] }
            val fb = BooleanArray(xb.size) { xb[it] >= thr[j] }
            val res = binaryNull(fa, fb) + analyticNull(xa, xb, v.ranks[i], v.ranks[j], !v.constant[i] && !v.constant[j])
            val row = linkedMapOf<String, Any?>(
                "node_a" to sc.nodes[i].key, "node_b" to sc.nodes[j].key, "cls" to cls,
                "relation" to relation(sc.nodes[i], sc.nodes[j]),
                "flag_rate_a" to fa.count { it }.toDouble() / fa.size,
                "flag_rate_b" to fb.count { it }.toDouble() / fb.size
            )
            for (m in NULL_METRICS) {
                val d = res[m]
                row["${m}_obs"] = d?.obs
                row["${m}_null_mean"] = d?.mean
                row["${m}_null_sd"] = d?.sd
                row["${m}_p"] = d?.p
                row["${m}_excess"] = d?.let { it.obs - it.mean }
                row["${m}_z"] = d?.let { (it.obs - it.mean) / it.sd }
            }
            rows += row
        }
    }
    for (m in NULL_METRICS) {
        for ((cls, _) in CLASSES) {
            val sel = rows.filter { it["cls"] == cls }
            val q = bh(DoubleArray(sel.size) { (sel[it]["${m}_p"] as? Double)?.takeIf { p -> !p.isNaN() } ?: 1.0 })
            sel.forEachIndexed { k, r -> r["${m}_q"] = q[k] }
        }
    }
    writeCsv(File(out, "null_calibrated$suffix.csv"), rows)

    nullHeatmaps(rows, idx.map { sc.nodes[it].key }, heatmapMetric, fdr, leavesOnly, File(out, "${heatmapMetric}_heatmaps$suffix"))

    println("pairs significantly above independence (FDR $fdr), kappa:")
    rows.groupBy { it["cls"] to it["relation"] }.toSortedMap(compareBy({ it.first as String }, { it.second as String }))
        .forEach { (k, g) ->
            println("  %-10s %-14s %d / %d".format(k.first, k.second, g.count { (it["kappa_q"] as Double) < fdr }, g.size))
        }
    println("mean excess over null:")
    printMeans(rows, NULL_METRICS.map { "${it}_excess" })
}
