package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.util.hp
import kotlin.math.max

fun frontDistance(
    parent: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    child: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?
): Double {
    // Extract sorted (x=SupportX, y=Conf) pairs; sorted by x asc
    fun toRawPoints(
        front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?
    ): List<Pair<Double, Double>> {
        if (front == null || front.isEmpty) return emptyList()

        return front.mapNotNull {
            val f = it.fitness().data()
            val support = f[0]
            val conf = f[1]

            if (support < hp.minSupport || support > hp.maxSupport) {
                null
            } else {
                support to conf
            }
        }.sortedBy { it.first }
    }

    val parentRaw = toRawPoints(parent)
    val childRaw = toRawPoints(child)

    if (parentRaw.size < 2 || childRaw.size < 2) return 0.0

    val left = maxOf(
        hp.minSupport.toDouble(),
        parentRaw.minOf { it.first },
        childRaw.minOf { it.first }
    )

    val right = minOf(
        hp.maxSupport.toDouble(),
        parentRaw.maxOf { it.first },
        childRaw.maxOf { it.first }
    )

    val supRange = right - left
    if (supRange <= 0.0) return 0.0

    fun normalize(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        points
            .filter { (support, _) -> support in left..right }
            .map { (support, conf) ->
                val xNorm = (support - left) / supRange
                xNorm to conf
            }
            .sortedBy { it.first }

    val pPts = normalize(parentRaw)
    val cPts = normalize(childRaw)

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
        out.filter { it in xL..xR }
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
