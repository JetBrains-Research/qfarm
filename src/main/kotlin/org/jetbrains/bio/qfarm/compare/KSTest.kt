package org.jetbrains.bio.qfarm.compare

data class KsResult(
    val d: Double,
    val pValue: Double
)

fun normalize(h: IntArray): DoubleArray {
    val sum = h.sum().toDouble()
    if (sum == 0.0) return DoubleArray(h.size) { 0.0 }
    return DoubleArray(h.size) { i -> h[i] / sum }
}

fun toCdf(p: DoubleArray): DoubleArray {
    val cdf = DoubleArray(p.size)
    var acc = 0.0
    for (i in p.indices) {
        acc += p[i]
        cdf[i] = acc
    }
    return cdf
}

fun ksStatistic(cdfA: DoubleArray, cdfB: DoubleArray): Double {
    var maxDiff = 0.0
    for (i in cdfA.indices) {
        val diff = kotlin.math.abs(cdfA[i] - cdfB[i])
        if (diff > maxDiff) maxDiff = diff
    }
    return maxDiff
}

fun ksPValue(d: Double, n: Int): Double {
    if (n <= 0) return 1.0
    val en = kotlin.math.sqrt(n.toDouble())
    val lambda = (en + 0.12 + 0.11 / en) * d
    return 2 * kotlin.math.exp(-2 * lambda * lambda)
}

fun ksTest(hA: IntArray, hB: IntArray): KsResult {
    val pA = normalize(hA)
    val pB = normalize(hB)

    val cdfA = toCdf(pA)
    val cdfB = toCdf(pB)

    val d = ksStatistic(cdfA, cdfB)

    val n1 = hA.sum()
    val n2 = hB.sum()
    val neff = if (n1 + n2 == 0) 1.0 else (n1 * n2) / (n1 + n2).toDouble()

    val p = ksPValue(d, neff.toInt())

    return KsResult(d, p)
}

fun ksPerNode(
    labelA: String?,
    labelB: String?
): KsResult {

    val hA = extractBarsFromLabel(labelA)
    val hB = extractBarsFromLabel(labelB)

    val attrs = hA.keys.intersect(hB.keys)

    if (attrs.isEmpty()) return KsResult(0.0, 1.0)

    val results = attrs.map { attr ->
        ksTest(hA[attr]!!, hB[attr]!!)
    }

    val pNode = results.minOf { it.pValue }
    val dNode = results.maxOf { it.d }

    return KsResult(dNode, pNode)
}
