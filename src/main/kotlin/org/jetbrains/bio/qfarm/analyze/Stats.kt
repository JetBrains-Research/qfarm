package org.jetbrains.bio.qfarm.analyze

import org.apache.commons.math3.distribution.BinomialDistribution
import org.apache.commons.math3.distribution.HypergeometricDistribution
import org.apache.commons.math3.special.Erf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Average ranks (1-based), like scipy.stats.rankdata. */
fun rankData(x: DoubleArray): DoubleArray {
    val idx = x.indices.sortedBy { x[it] }
    val r = DoubleArray(x.size)
    var i = 0
    while (i < idx.size) {
        var j = i
        while (j + 1 < idx.size && x[idx[j + 1]] == x[idx[i]]) j++
        val avg = (i + j) / 2.0 + 1
        for (k in i..j) r[idx[k]] = avg
        i = j + 1
    }
    return r
}

/** Population standard deviation (numpy default, ddof = 0). */
fun std(x: DoubleArray): Double {
    val m = x.average()
    return sqrt(x.sumOf { (it - m) * (it - m) } / x.size)
}

fun pearson(a: DoubleArray, b: DoubleArray): Double {
    val ma = a.average()
    val mb = b.average()
    var sab = 0.0
    var saa = 0.0
    var sbb = 0.0
    for (i in a.indices) {
        val da = a[i] - ma
        val db = b[i] - mb
        sab += da * db
        saa += da * da
        sbb += db * db
    }
    return sab / sqrt(saa * sbb)
}

/** Spearman correlation, given precomputed average ranks. */
fun spearmanFromRanks(ra: DoubleArray, rb: DoubleArray) = pearson(ra, rb)

/**
 * Kendall tau-b for integer scores (scipy.stats.kendalltau default), in O(n + U*V)
 * via the contingency table of the two score vectors.
 */
fun kendallTauB(a: IntArray, b: IntArray): Double {
    val ua = a.distinct().sorted()
    val ub = b.distinct().sorted()
    val ia = ua.withIndex().associate { (i, v) -> v to i }
    val ib = ub.withIndex().associate { (i, v) -> v to i }
    val nu = ua.size
    val nv = ub.size
    val t = Array(nu) { LongArray(nv) }
    for (i in a.indices) t[ia[a[i]]!!][ib[b[i]]!!]++

    // above[u][v] = number of pairs with row > u and column > v; below: row > u, column < v
    val gt = Array(nu + 1) { LongArray(nv + 2) }
    for (u in nu - 1 downTo 0) for (v in nv - 1 downTo 0)
        gt[u][v] = t[u][v] + gt[u + 1][v] + gt[u][v + 1] - gt[u + 1][v + 1]
    var conc = 0L
    var disc = 0L
    for (u in 0 until nu) for (v in 0 until nv) {
        val c = t[u][v]
        if (c == 0L) continue
        conc += c * gt[u + 1][v + 1]
        val rowsBelow = gt[u + 1][0]
        val lessEq = rowsBelow - gt[u + 1][v]          // rows > u, column < v
        disc += c * lessEq
    }
    val n = a.size.toLong()
    val n0 = n * (n - 1) / 2.0
    val n1 = a.asIterable().groupingBy { it }.eachCount().values.sumOf { it.toLong() * (it - 1) / 2 }
    val n2 = b.asIterable().groupingBy { it }.eachCount().values.sumOf { it.toLong() * (it - 1) / 2 }
    return (conc - disc) / sqrt((n0 - n1) * (n0 - n2))
}

fun normalSf(z: Double) = 0.5 * Erf.erfc(z / sqrt(2.0))

/** Two-sided exact binomial test with p = 0.5 (scipy.stats.binomtest). */
fun binomTestHalf(k: Int, n: Int): Double {
    if (n == 0) return 1.0
    val d = BinomialDistribution(null, n, 0.5)
    return min(1.0, 2 * d.cumulativeProbability(min(k, n - k)))
}

fun hypergeom(n: Int, ka: Int, kb: Int) = HypergeometricDistribution(null, n, ka, kb)

/** Benjamini-Hochberg q-values. */
fun bh(p: DoubleArray): DoubleArray {
    val m = p.size
    val o = p.indices.sortedBy { p[it] }
    val q = DoubleArray(m)
    var running = Double.POSITIVE_INFINITY
    for (r in m - 1 downTo 0) {
        running = min(running, p[o[r]] * m / (r + 1))
        q[o[r]] = min(running, 1.0)
    }
    return q
}

/** Mean silhouette over a full distance matrix (port of the Python helper). */
fun silhouette(d: Array<DoubleArray>, labels: IntArray): Double {
    val ks = labels.distinct().sorted()
    if (ks.size < 2) return Double.NaN
    val members = ks.map { k -> labels.indices.filter { labels[it] == k }.toIntArray() }
    var total = 0.0
    for (i in labels.indices) {
        val own = ks.indexOf(labels[i])
        val nOwn = members[own].size
        if (nOwn == 1) continue
        val meanTo = members.map { m -> m.sumOf { d[i][it] } / m.size }
        val a = members[own].sumOf { d[i][it] } / (nOwn - 1)
        val b = meanTo.filterIndexed { j, _ -> j != own }.min()
        total += if (max(a, b) > 0) (b - a) / max(a, b) else 0.0
    }
    return total / labels.size
}

/** Adjusted Rand index. */
fun ari(a: IntArray, b: IntArray): Double {
    val pairs = a.indices.groupingBy { a[it] to b[it] }.eachCount().values
    val ra = a.toList().groupingBy { it }.eachCount().values
    val rb = b.toList().groupingBy { it }.eachCount().values
    fun comb(x: Int) = x.toDouble() * (x - 1) / 2
    val sc = pairs.sumOf { comb(it) }
    val sa = ra.sumOf { comb(it) }
    val sb = rb.sumOf { comb(it) }
    val exp = sa * sb / comb(a.size)
    val den = (sa + sb) / 2 - exp
    return if (den != 0.0) (sc - exp) / den else 1.0
}

/** Euclidean distances between the rows of [z]. */
fun euclid(z: Array<DoubleArray>): Array<DoubleArray> = Array(z.size) { i ->
    DoubleArray(z.size) { j ->
        var s = 0.0
        for (c in z[i].indices) {
            val d = z[i][c] - z[j][c]
            s += d * d
        }
        sqrt(s)
    }
}
