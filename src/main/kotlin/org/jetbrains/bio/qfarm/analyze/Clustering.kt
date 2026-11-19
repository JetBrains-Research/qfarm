package org.jetbrains.bio.qfarm.analyze

import java.io.File
import java.util.SplittableRandom
import kotlin.math.ceil
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sqrt

class ClusterParams(
    val kRange: IntRange,
    val kFixed: Int?,
    val bootstraps: Int,
    val seed: Long
)

private val UNIT_TOKENS = setOf(
    "mg", "dl", "percent", "pct", "g", "u", "l", "mmol", "fl", "pg", "kg", "m2", "1000", "cells", "ul", "years"
)

/** "bun_mg_dl + rdw_percent" -> "bun + rdw" (drops trailing unit tokens of each attribute). */
fun shortName(node: TreeNode): String = node.attributes.joinToString(" + ") { a ->
    val parts = a.split("_").toMutableList()
    while (parts.size > 1 && parts.last().lowercase() in UNIT_TOKENS) parts.removeAt(parts.lastIndex)
    parts.joinToString("_")
}

private fun sub(d: Array<DoubleArray>, idx: IntArray) = Array(idx.size) { i -> DoubleArray(idx.size) { j -> d[idx[i]][idx[j]] } }

private fun zScoreColumns(x: Array<DoubleArray>): Array<DoubleArray> {
    val cols = x.firstOrNull()?.size ?: 0
    val out = Array(x.size) { DoubleArray(cols) }
    for (c in 0 until cols) {
        val col = DoubleArray(x.size) { x[it][c] }
        val m = col.average()
        val sd = std(col).let { if (it > 0) it else 1.0 }
        for (r in x.indices) out[r][c] = (col[r] - m) / sd
    }
    return out
}

private fun transform(raw: Array<DoubleArray>, method: String): Array<DoubleArray> = when (method) {
    "log1p_z" -> zScoreColumns(Array(raw.size) { r -> DoubleArray(raw[r].size) { ln1p(raw[r][it]) } })
    "z" -> zScoreColumns(raw)
    "rank_z" -> {
        val cols = raw.firstOrNull()?.size ?: 0
        val ranks = (0 until cols).map { c -> rankData(DoubleArray(raw.size) { raw[it][c] }) }
        zScoreColumns(Array(raw.size) { r -> DoubleArray(cols) { ranks[it][r] } })
    }
    else -> error("Unknown transform '$method' (use log1p_z, z or rank_z)")
}

private fun writeMatrix(file: File, keys: List<String>, m: Array<DoubleArray>) =
    writeCsv(file, keys.indices.map { i -> linkedMapOf<String, Any?>("" to keys[i]) + keys.indices.associate { keys[it] to m[i][it] } })

/** cluster_donors.py for one class. */
fun clusterDonors(
    sc: NodeScores, cls: String, transformName: String, linkageName: String,
    minClusterSize: Int, minClusterFrac: Double, scoreMin: Int, topFrac: Double?,
    p: ClusterParams, out: File
) {
    val rows = sc.indicesOf(cls == "positive")
    val nodes = sc.nodes
    val raw = Array(rows.size) { r -> DoubleArray(nodes.size) { sc.scores[it][rows[r]].toDouble() } }
    val z = transform(raw, transformName)
    val d = euclid(z)
    val n = rows.size
    val minSize = max(minClusterSize, ceil(minClusterFrac * n).toInt())
    val rng = SplittableRandom(p.seed)
    println("donor clustering, $cls: $n donors, ${nodes.size} nodes, min cluster size $minSize")

    val tree = linkage(d, linkageName)
    val kRows = mutableListOf<Map<String, Any?>>()
    var best: Triple<Double, Int, IntArray>? = null
    for (k in p.kFixed?.let { listOf(it) } ?: p.kRange.toList()) {
        val cut = tree.cut(k)
        val sizes = cut.toList().groupingBy { it }.eachCount()
        val lab = IntArray(n) { if (sizes.getValue(cut[it]) >= minSize) cut[it] else 0 }
        val real = lab.indices.filter { lab[it] > 0 }.toIntArray()
        val nReal = real.map { lab[it] }.distinct().size
        val s = if (nReal >= 2) silhouette(sub(d, real), IntArray(real.size) { lab[real[it]] }) else Double.NaN
        kRows += linkedMapOf("k_cut" to k, "clusters_kept" to nReal, "outlier_donors" to n - real.size, "silhouette" to s)
        if (nReal >= 2 && (best == null || s > best.first || p.kFixed != null)) best = Triple(s, k, lab)
    }
    val dir = File(out, "clustering").apply { mkdirs() }
    writeCsv(File(dir, "donor_k_selection_$cls.csv"), kRows)
    kRows.forEach { println("  k=%d kept=%d outliers=%d silhouette=%.3f".format(it["k_cut"], it["clusters_kept"], it["outlier_donors"], it["silhouette"])) }
    val (_, k, lab) = best ?: run {
        println("  no cut leaves 2 clusters of at least $minSize donors; lower --min-cluster-size/--min-cluster-frac")
        return
    }

    // cluster-wise Jaccard stability (Hennig): recluster bootstrap samples, best match per original cluster
    val ids = lab.filter { it > 0 }.distinct().sorted()
    val stabSum = DoubleArray(ids.size)
    val stabCnt = IntArray(ids.size)
    repeat(p.bootstraps) {
        val idx = IntArray(n) { rng.nextInt(n) }.distinct().sorted().toIntArray()
        val lb = linkage(sub(d, idx), linkageName).cut(k)
        val byQ = lb.indices.groupBy { lb[it] }.values.map { it.toSet() }
        for ((ci, c) in ids.withIndex()) {
            val a = idx.indices.filter { lab[idx[it]] == c }.toSet()
            if (a.isEmpty()) continue
            stabSum[ci] += byQ.maxOf { q -> (a intersect q).size.toDouble() / (a union q).size }
            stabCnt[ci]++
        }
    }
    val stability = ids.indices.associate { ids[it] to if (stabCnt[it] > 0) stabSum[it] / stabCnt[it] else Double.NaN }

    writeCsv(File(dir, "donor_clusters_$cls.csv"), rows.indices.map {
        linkedMapOf<String, Any?>("donor_id" to sc.donorIds[rows[it]], "cluster" to lab[it])
    })

    val thr = sc.scores.map { flagThreshold(it, scoreMin, topFrac) }
    val clusters = lab.distinct().sorted()
    val members = clusters.associateWith { c -> lab.indices.filter { lab[it] == c } }
    fun colMean(m: List<Int>, f: (Int) -> Double) = m.sumOf(f) / m.size
    val meanScore = clusters.map { c -> DoubleArray(nodes.size) { j -> colMean(members.getValue(c)) { raw[it][j] } } }
    val share = clusters.map { c -> DoubleArray(nodes.size) { j -> colMean(members.getValue(c)) { if (raw[it][j] >= thr[j]) 1.0 else 0.0 } } }
    val meanZ = clusters.map { c -> DoubleArray(nodes.size) { j -> colMean(members.getValue(c)) { z[it][j] } } }
    writeCsv(File(dir, "donor_cluster_profiles_$cls.csv"), clusters.mapIndexed { ci, c ->
        linkedMapOf<String, Any?>("cluster" to c, "size" to members.getValue(c).size, "stability_jaccard" to stability[c]) +
                nodes.indices.associate { "mean_score_${nodes[it].key}" to meanScore[ci][it] } +
                nodes.indices.associate { "flagged_${nodes[it].key}" to share[ci][it] }
    })

    // nodes as rows, clusters as columns; rows ordered by similarity of their profiles
    val m = Array(nodes.size) { j -> DoubleArray(clusters.size) { meanZ[it][j] } }
    val s = Array(nodes.size) { j -> DoubleArray(clusters.size) { share[it][j] } }
    val order = if (nodes.size > 2) linkage(euclid(m), "average").leaves() else nodes.indices.toList().toIntArray()
    val colLabels = clusters.map { c ->
        val size = members.getValue(c).size
        if (c == 0) "outliers\nn=$size" else "c$c\nn=$size\nstab %.2f".format(stability[c])
    }
    val flagRule = if (topFrac == null) "score >= $scoreMin" else "node score in its top ${fmtPct(topFrac)}%"
    donorProfilePlot(
        nodes.map(::shortName), order, colLabels, m, s,
        "mean standardised score ($transformName)", "share flagged ($flagRule)",
        "$cls donors, k=$k", File(dir, "donor_cluster_profiles_$cls")
    )
    println("  cut k = $k (cluster 0 = outliers)")
    clusters.forEach { println("  cluster %d: size %d, stability %.2f".format(it, members.getValue(it).size, stability[it] ?: Double.NaN)) }
}

/** items x features booleans -> Jaccard distance (0/0 -> 1, diagonal 0). */
private fun jaccardDistance(f: List<BooleanArray>): Array<DoubleArray> {
    val s = f.map { v -> v.count { it } }
    return Array(f.size) { i ->
        DoubleArray(f.size) { j ->
            if (i == j) 0.0 else {
                val inter = f[i].indices.count { f[i][it] && f[j][it] }
                val union = s[i] + s[j] - inter
                1 - if (union > 0) inter.toDouble() / union else 0.0
            }
        }
    }
}

/** items x donors scores -> 1 - Spearman correlation (constant item -> 1). */
private fun spearmanDistance(x: List<DoubleArray>): Array<DoubleArray> {
    val r = x.map { v ->
        val rk = rankData(v)
        val m = rk.average()
        DoubleArray(rk.size) { rk[it] - m }
    }
    val norm = r.map { v -> sqrt(v.sumOf { it * it }) }
    return Array(x.size) { i ->
        DoubleArray(x.size) { j ->
            if (i == j) 0.0 else {
                val c = r[i].indices.sumOf { r[i][it] * r[j][it] } / (norm[i] * norm[j])
                (1 - if (c.isNaN()) 0.0 else c).coerceIn(0.0, 2.0)
            }
        }
    }
}

/** cluster_nodes.py: node clusters per class from donor-level agreement, with bootstrap stability. */
fun clusterNodes(
    sc: NodeScores, metric: String, linkageName: String, scoreMin: Int, topFrac: Double?,
    p: ClusterParams, out: File
) {
    require(metric == "spearman" || metric == "jaccard") { "Unknown node metric '$metric' (use spearman or jaccard)" }
    val nodes = sc.nodes
    val keys = nodes.map { it.key }
    val thr = sc.scores.map { flagThreshold(it, scoreMin, topFrac) }
    val rng = SplittableRandom(p.seed)
    val dir = File(out, "clustering").apply { mkdirs() }

    // rows: donors of the class (possibly resampled); returns nodes x nodes distance
    fun distance(rows: IntArray): Array<DoubleArray> =
        if (metric == "jaccard") jaccardDistance(nodes.indices.map { j -> BooleanArray(rows.size) { sc.scores[j][rows[it]] >= thr[j] } })
        else spearmanDistance(nodes.indices.map { j -> DoubleArray(rows.size) { sc.scores[j][rows[it]].toDouble() } })

    fun chooseK(d: Array<DoubleArray>): Pair<Int, Double> {
        p.kFixed?.let { return it to Double.NaN }
        var best: Pair<Int, Double>? = null
        for (k in p.kRange) {
            if (k >= d.size) break
            val s = silhouette(d, linkage(d, linkageName).cut(k))
            if (!s.isNaN() && (best == null || s > best.second)) best = k to s
        }
        return best ?: run {
            println("WARNING: no k gave a valid silhouette (nodes probably all identical); using k=2")
            2 to Double.NaN
        }
    }

    class Result(val k: Int, val tree: Linkage, val lab: IntArray, val coph: DoubleArray, val co: Array<DoubleArray>, val stab: DoubleArray)

    val summary = mutableListOf<Map<String, Any?>>()
    val res = CLASSES.associate { (cls, pos) ->
        val rows = sc.indicesOf(pos)
        val d = distance(rows)
        val (k, sil) = chooseK(d)
        val tree = linkage(d, linkageName)
        val lab = tree.cut(k)
        val coph = tree.cophenetic()
        val cc = pearson(coph, condensed(d))

        val co = Array(nodes.size) { DoubleArray(nodes.size) }
        val aris = DoubleArray(p.bootstraps)
        for (b in 0 until p.bootstraps) {
            val lb = linkage(distance(IntArray(rows.size) { rows[rng.nextInt(rows.size)] }), linkageName).cut(k)
            for (i in nodes.indices) for (j in nodes.indices) if (lb[i] == lb[j]) co[i][j] += 1.0
            aris[b] = ari(lab, lb)
        }
        co.forEach { r -> for (j in r.indices) r[j] /= p.bootstraps.toDouble() }
        val stab = DoubleArray(nodes.size) { i -> nodes.indices.filter { lab[it] == lab[i] }.map { co[i][it] }.average() }

        summary += linkedMapOf(
            "class" to cls, "n_donors" to rows.size, "k" to k, "silhouette" to sil, "cophenetic_corr" to cc,
            "bootstrap_ari_mean" to aris.average(), "bootstrap_ari_sd" to std(aris)
        )
        writeMatrix(File(dir, "node_distance_${cls}_$metric.csv"), keys, d)
        writeMatrix(File(dir, "node_coclustering_${cls}_$metric.csv"), keys, co)
        cls to Result(k, tree, lab, coph, co, stab)
    }

    val a = res.getValue("positive")
    val b = res.getValue("negative")
    summary += linkedMapOf(
        "class" to "positive_vs_negative", "k" to "${a.k}/${b.k}",
        "cophenetic_corr" to pearson(a.coph, b.coph),
        "ari_between_classes" to if (a.k == b.k) ari(a.lab, b.lab) else Double.NaN
    )
    writeCsv(File(dir, "cluster_summary_$metric.csv"), summary.map { row ->
        listOf("class", "n_donors", "k", "silhouette", "cophenetic_corr", "bootstrap_ari_mean", "bootstrap_ari_sd", "ari_between_classes")
            .associateWith { row[it] }
    })
    writeCsv(File(dir, "node_clusters_$metric.csv"), nodes.indices.map { i ->
        linkedMapOf(
            "node_key" to keys[i], "root_attribute" to nodes[i].attributes.first(),
            "attributes" to nodes[i].attributes.joinToString(","), "depth" to nodes[i].depth,
            "cluster_positive" to a.lab[i], "stability_positive" to a.stab[i],
            "cluster_negative" to b.lab[i], "stability_negative" to b.stab[i]
        )
    })

    val names = nodes.map(::shortName)
    for ((cls, r) in res) {
        nodeDendrogramPlot(names, r.tree, r.lab, r.co, "1 - $metric", "${cls}: node clusters (k=${r.k})",
            File(dir, "node_dendrogram_${cls}_$metric"))
    }
    summary.forEach { row -> println("  " + row.entries.joinToString("  ") { (k, v) -> "$k=" + (if (v is Double) "%.3f".format(v) else v) }) }
}
