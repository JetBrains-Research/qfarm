package org.jetbrains.bio.qfarm.compare

import org.jetbrains.bio.qfarm.params.hp

data class KsResult(
    val d: Double,
    val pValue: Double,
    val pass: Boolean
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

fun ksPValue(d: Double, n: Double): Double {
    if (n <= 0.0) return 1.0

    val en = kotlin.math.sqrt(n)
    val lambda = (en + 0.12 + 0.11 / en) * d

    if (lambda < 1e-8) return 1.0

    var sum = 0.0
    val maxK = 5  // 3–5 is enough

    for (k in 1..maxK) {
        val term = kotlin.math.exp(-2.0 * k * k * lambda * lambda)
        sum += if (k % 2 == 1) term else -term
    }

    val p = 2.0 * sum

    return p.coerceIn(0.0, 1.0)
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

    val p = ksPValue(d, neff)

    return KsResult(
        d = d,
        pValue = p,
        pass = false // not used at this level
    )
}

fun holmPass(pValues: List<Double>, alpha: Double): Boolean {

    if (pValues.isEmpty()) return true

    val sorted = pValues.sorted()

    for (i in sorted.indices) {
        val threshold = alpha / (sorted.size - i)
        if (sorted[i] < threshold) {
            return false // reject null → KS FAIL
        }
    }

    return true // all passed → KS PASS
}

fun ksPerNode(
    labelA: String?,
    labelB: String?
): KsResult {

    val hA = extractBarsFromLabel(labelA)
    val hB = extractBarsFromLabel(labelB)

    val attrs = hA.keys.intersect(hB.keys)

    if (attrs.isEmpty()) {
        return KsResult(
            d = 0.0,
            pValue = 1.0,
            pass = true
        )
    }

    val results = attrs.map { attr ->
        ksTest(hA[attr]!!, hB[attr]!!)
    }

    val pValues = results.map { it.pValue }

    val ksPass = holmPass(pValues, hp.alphaThreshold)

    val dNode = results.maxOf { it.d }

    val pNode = pValues.minOrNull() ?: 1.0 // informational only

    return KsResult(
        d = dNode,
        pValue = pNode,
        pass = ksPass
    )
}
