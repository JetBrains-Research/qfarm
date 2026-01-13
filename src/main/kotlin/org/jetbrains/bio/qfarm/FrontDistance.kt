package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import kotlin.math.max

fun frontDistance(
    parent: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    child: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?
): Double {
    val supRange = hp.maxSupport - hp.minSupport

    // Extract sorted (x=SupportX, y=Lift) pairs; sorted by x asc
    fun toPoints(front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?): List<Pair<Double, Double>> {
        if (front == null || front.isEmpty) return emptyList()

        return front.mapNotNull {
            val f = it.fitness().data()
            val support = f[0]
            val lift = f[1]

            if (support < hp.minSupport || support > hp.maxSupport) {
                null
            } else {
                val xNorm = (support - hp.minSupport) / supRange
                xNorm to lift
            }
        }.sortedBy { it.first }
    }

    val pPts = toPoints(parent)
    val cPts = toPoints(child)

    if (cPts.size < 2) return 0.0

    if (pPts.size < 2) {
        var area = 0.0
        for (i in 0 until cPts.lastIndex) {
            val (x0, y0) = cPts[i]
            val (x1, y1) = cPts[i + 1]
            if (x1 > x0) {
                val w = x1 - x0
                area += w * (y0 + y1) * 0.5
            }
        }
        return area
    }

    // Overlap window
    val xL = maxOf(pPts.first().first, cPts.first().first)
    val xR = minOf(pPts.last().first,  cPts.last().first)
    if (xL >= xR) return 0.0

    // Build sorted unique breakpoints inside [xL, xR] by merging x's from both fronts
    fun xsInWindow(pts: List<Pair<Double, Double>>): List<Double> {
        val res = ArrayList<Double>(pts.size + 2)
        // advance to first inside window
        var i = pts.indexOfFirst { it.first >= xL }
        if (i < 0) i = pts.lastIndex
        // include also the left neighbor if exists and crosses xL
        if (i > 0 && pts[i - 1].first < xL) i -= 1
        while (i < pts.size && pts[i].first <= xR) {
            res.add(pts[i].first)
            i++
        }
        return res
    }

    val xsMerged = run {
        val a = xsInWindow(pPts)
        val b = xsInWindow(cPts)
        val out = ArrayList<Double>(a.size + b.size + 2)
        var i = 0; var j = 0
        fun addIfNew(x: Double) {
            if (out.isEmpty() || out.last() != x) out.add(x)
        }
        addIfNew(xL)
        while (i < a.size && j < b.size) {
            val xa = a[i]; val xb = b[j]
            when {
                xa < xb -> { addIfNew(xa); i++ }
                xb < xa -> { addIfNew(xb); j++ }
                else    -> { addIfNew(xa); i++; j++ }
            }
        }
        while (i < a.size) { addIfNew(a[i++]) }
        while (j < b.size) { addIfNew(b[j++]) }
        addIfNew(xR)
        // ensure strictly increasing & within [xL,xR]
        out.filter { it >= xL && it <= xR }
    }
    if (xsMerged.size < 2) return 0.0

    // Linear interpolation with a moving pointer; assumes xsMerged is increasing
    class Interp(private val pts: List<Pair<Double, Double>>) {
        private var i = 0
        fun yAt(x: Double): Double {
            // move i so that pts[i].x <= x <= pts[i+1].x
            // ensure bounds
            if (x <= pts.first().first) return pts.first().second
            if (x >= pts.last().first)  return pts.last().second
            while (i < pts.lastIndex && pts[i + 1].first < x) i++
            // if x is left of current segment (possible at first call), rewind
            while (i > 0 && pts[i].first > x) i--
            // handle runs of equal x
            val x0 = pts[i].first; val y0 = pts[i].second
            var x1 = pts[i + 1].first; var y1 = pts[i + 1].second
            // if dx == 0, walk forward until non-zero or end
            var k = i
            while (x1 == x0 && k < pts.lastIndex) {
                k++
                x1 = pts[k].first; y1 = pts[k].second
            }
            val dx = x1 - x0
            return if (dx == 0.0) max(y0, y1)
            else y0 + (x - x0) * (y1 - y0) / dx
        }
    }

    val pInterp = Interp(pPts)
    val cInterp = Interp(cPts)

    // Trapezoidal areas on the common grid
    var parentArea = 0.0
    var childArea  = 0.0
    var k = 0
    while (k < xsMerged.lastIndex) {
        val a = xsMerged[k]
        val b = xsMerged[k + 1]
        if (b > a) {
            val pYa = pInterp.yAt(a); val pYb = pInterp.yAt(b)
            val cYa = cInterp.yAt(a); val cYb = cInterp.yAt(b)
            val w = b - a
            parentArea += w * (pYa + pYb) * 0.5
            childArea  += w * (cYa + cYb) * 0.5
        }
        k++
    }

    return childArea - parentArea
}



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
