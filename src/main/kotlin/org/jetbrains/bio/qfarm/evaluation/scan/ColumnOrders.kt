package org.jetbrains.bio.qfarm.evaluation.scan

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * Rows of one column in ascending value order, with the runs of equal values ("groups").
 *
 * `order[p]` is the row at sorted position p, `rank[row]` its position. `groupStart[p]` and
 * `groupEnd[p]` are the first position and the exclusive last position of the run of equal
 * values that position p belongs to. A window of positions that starts at a group start and
 * ends at a group end is exactly the set of rows whose value lies in a closed interval, which
 * is what makes a window a QFARM rule condition.
 */
class ColumnOrder(
    val order: IntArray,
    val rank: IntArray,
    val groupStart: IntArray,
    val groupEnd: IntArray,
    /** Number of distinct values. */
    val groups: Int
) {
    val size: Int get() = order.size
}

/**
 * Per-column orders of a dataset, computed on first use and cached (one column of a wide dataset
 * is only paid for when it is scanned). Values must be finite, as for the KD oracle.
 */
class ColumnOrders(private val data: List<DoubleArray>) {

    private val cache = ConcurrentHashMap<Int, ColumnOrder>()

    val rows: Int get() = data.size

    fun of(column: Int): ColumnOrder = cache.computeIfAbsent(column) { build(it) }

    /** Value of the row at sorted position [pos] of [column]. */
    fun valueAt(column: Int, pos: Int): Double = data[of(column).order[pos]][column]

    private fun build(column: Int): ColumnOrder {
        val n = data.size
        val values = DoubleArray(n) { row ->
            val v = data[row][column]
            require(v.isFinite()) { "Row $row, attribute $column contains NaN or Infinity: $v" }
            v
        }

        val boxed = Array(n) { it }
        java.util.Arrays.sort(boxed) { a, b ->
            val c = values[a].compareTo(values[b])
            if (c != 0) c else a.compareTo(b)
        }
        val order = IntArray(n) { boxed[it] }

        val rank = IntArray(n)
        for (p in 0 until n) rank[order[p]] = p

        val groupStart = IntArray(n)
        val groupEnd = IntArray(n)
        var groups = 0
        var p = 0
        while (p < n) {
            var q = p + 1
            while (q < n && values[order[q]] == values[order[p]]) q++
            for (i in p until q) {
                groupStart[i] = p
                groupEnd[i] = q
            }
            groups++
            p = q
        }

        return ColumnOrder(order, rank, groupStart, groupEnd, groups)
    }

    /** ln(i!) for i in 0..rows, for hypergeometric tails; computed once. */
    val logFactorial: DoubleArray by lazy {
        val n = data.size
        val t = DoubleArray(n + 1)
        for (i in 1..n) t[i] = t[i - 1] + ln(i.toDouble())
        t
    }

    fun logChoose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return Double.NEGATIVE_INFINITY
        val lf = logFactorial
        return lf[n] - lf[k] - lf[n - k]
    }

    /**
     * P(X >= x) for X ~ Hypergeometric(population [n], successes [k], draws [s]).
     * Terms are summed from x upwards until they stop contributing.
     */
    fun hypergeometricTail(n: Int, k: Int, s: Int, x: Int): Double {
        if (x <= 0) return 1.0
        val hi = minOf(k, s)
        if (x > hi) return 0.0
        val denom = logChoose(n, s)
        var sum = 0.0
        for (i in x..hi) {
            val term = Math.exp(logChoose(k, i) + logChoose(n - k, s - i) - denom)
            sum += term
            if (term < sum * 1e-15) break
        }
        return sum.coerceIn(0.0, 1.0)
    }
}
