package org.jetbrains.bio.qfarm.evaluation.fronts

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene

/**
 * Compute the vertical distance from a single individual to the otherFront,
 * using a trapezoid/piecewise-linear construction of the front.
 *
 * - X axis: f[0] (Support or "SupportX")
 * - Y axis: f[1] (Lift)
 * - Interpolates the segment of `otherFront` at individual's X.
 * - If individual lies above the front (negative distance), returns 0.0.
 * - Outside the front's X-range, uses the endpoint's Y (rectangle extension).
 */
private const val EPS = 1e-12

// ---------- Internal helper: preprocess a front to sorted primitive arrays ----------
private data class Polyline(val xs: DoubleArray, val ys: DoubleArray) {
    val n: Int get() = xs.size
}

private fun preprocessFront(
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?
): Polyline? {
    if (front == null || front.isEmpty) return null
    // Extract and sort by x asc
    val pairs = front.map {
        val f = it.fitness().data()
        f[0] to f[1]
    }.sortedBy { it.first }

    val n = pairs.size
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    var i = 0
    for ((x, y) in pairs) {
        xs[i] = x
        ys[i] = y
        i++
    }
    return Polyline(xs, ys)
}

// Find last index i s.t. xs[i] <= x (upperBound-1). Assumes xs increasing or non-decreasing.
private fun lastIndexLE(xs: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = xs.lastIndex
    // handle outside early
    if (x < xs[0]) return -1
    if (x >= xs[hi]) return hi
    // invariant: xs[lo] <= x < xs[hi]
    while (lo + 1 < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] <= x) lo = mid else hi = mid
    }
    return lo
}

// y(x) on a polyline with optional vertical runs:
// - if x outside range: clamps to endpoint Y
// - if segment has dx == 0: returns the max Y across that vertical run
private fun yAt(poly: Polyline, x: Double): Double {
    val xs = poly.xs
    val ys = poly.ys
    val n = poly.n
    if (n == 0) return 0.0
    if (n == 1) return ys[0]
    if (x <= xs[0]) return ys[0]
    if (x >= xs[n - 1]) return ys[n - 1]

    val i = lastIndexLE(xs, x) // -1 < i < n-1
    if (i < 0) return ys[0]
    val x0 = xs[i]; val y0 = ys[i]
    val x1 = xs[i + 1]; val y1 = ys[i + 1]
    val dx = x1 - x0
    if (dx == 0.0) {
        // Vertical run at x0==x1. Take envelope (max Y across the run).
        // Expand to full run [L..R] where xs[k] == x0
        var L = i
        while (L > 0 && xs[L - 1] == x0) L--
        var R = i + 1
        while (R < n - 1 && xs[R + 1] == x0) R++
        var best = ys[L]
        var k = L + 1
        while (k <= R) { if (ys[k] > best) best = ys[k]; k++ }
        return best
    }
    val t = (x - x0) / dx
    return y0 + t * (y1 - y0)
}

/**
 * Average vertical distance from every member of [frontA] to [frontB],
 * counting only strictly positive distances (same as your EPS rule).
 */
fun averageVerticalDistance(
    frontA: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    frontB: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
): Double {
    if (frontA.isEmpty || frontB.isEmpty) return 0.0

    val polyB = preprocessFront(frontB) ?: return 0.0
    var sum = 0.0
    var count = 0
    val it = frontA.iterator()
    while (it.hasNext()) {
        val p = it.next()
        val f = p.fitness().data()
        val x = f[0]; val y = f[1]
        val d = y - yAt(polyB, x)
        if (d > EPS) {
            sum += d
            count++
        }
    }
    return if (count == 0) 0.0 else sum / count
}
