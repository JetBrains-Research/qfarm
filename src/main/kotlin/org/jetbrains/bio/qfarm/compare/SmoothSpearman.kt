package org.jetbrains.bio.qfarm.compare

import org.jetbrains.bio.qfarm.params.hp
import kotlin.math.exp
import kotlin.math.sqrt

const val N_BINS = 20

data class SmoothedSpearmanResult(
    val distance: Double,
    val threshold: Double = hp.spearmanThreshold
) {
    val pass: Boolean
        get() = distance < threshold
}

fun gaussianKernel(
    sigma: Double = 1.0,
    radius: Int = 2
): List<Double> {
    val weights = (-radius..radius).map { x ->
        exp(-(x * x) / (2.0 * sigma * sigma))
    }

    val total = weights.sum()
    return weights.map { it / total }
}

fun smooth(
    hist: List<Double>,
    sigma: Double = 1.0,
    radius: Int = 2
): List<Double> {
    require(hist.isNotEmpty()) { "Histogram must not be empty" }

    val kernel = gaussianKernel(sigma, radius)
    val n = hist.size
    val out = MutableList(n) { 0.0 }

    for (i in 0 until n) {
        var value = 0.0

        for ((k, w) in kernel.withIndex()) {
            val offset = k - radius
            val j = (i + offset).coerceIn(0, n - 1)
            value += hist[j] * w
        }

        out[i] = value
    }

    return out
}

fun ranks(values: List<Double>): List<Double> {
    val indexed = values.withIndex().sortedBy { it.value }
    val out = MutableList(values.size) { 0.0 }

    var i = 0

    while (i < indexed.size) {
        var j = i

        while (
            j + 1 < indexed.size &&
            indexed[j + 1].value == indexed[i].value
        ) {
            j++
        }

        val avgRank = (i + j) / 2.0 + 1.0

        for (k in i..j) {
            val originalIndex = indexed[k].index
            out[originalIndex] = avgRank
        }

        i = j + 1
    }

    return out
}

fun normalizeZ(values: List<Double>): List<Double> {
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    val std = sqrt(variance)

    if (std == 0.0) {
        return List(values.size) { 0.0 }
    }

    return values.map { (it - mean) / std }
}

fun cosineDistance(a: List<Double>, b: List<Double>): Double {
    require(a.size == b.size) { "Vectors must have the same size" }

    val dot = a.zip(b).sumOf { (x, y) -> x * y }
    val normA = sqrt(a.sumOf { it * it })
    val normB = sqrt(b.sumOf { it * it })

    val sim = if (normA == 0.0 || normB == 0.0) {
        if (normA == normB) 1.0 else 0.0
    } else {
        dot / (normA * normB)
    }

    return (1.0 - sim) / 2.0
}

fun spearmanDistance(
    a: List<Double>,
    b: List<Double>
): Double {
    require(a.size == b.size) { "Histograms must have the same size" }
    require(a.isNotEmpty()) { "Histograms must not be empty" }

    return cosineDistance(
        normalizeZ(ranks(a)),
        normalizeZ(ranks(b))
    )
}

fun smoothedSpearmanDistance(
    a: List<Double>,
    b: List<Double>,
    sigma: Double = 1.0,
    radius: Int = 2
): Double {
    require(a.size == b.size) { "Histograms must have the same size" }
    require(a.isNotEmpty()) { "Histograms must not be empty" }

    val smoothA = smooth(a, sigma = sigma, radius = radius)
    val smoothB = smooth(b, sigma = sigma, radius = radius)

    return spearmanDistance(smoothA, smoothB)
}

fun smoothedSpearmanPerNode(
    oldLabel: String?,
    newLabel: String?,
    threshold: Double = hp.spearmanThreshold,
    sigma: Double = 1.0,
    radius: Int = 2
): SmoothedSpearmanResult {
    val oldBars = extractBarsFromLabel(oldLabel)
    val newBars = extractBarsFromLabel(newLabel)

    val attrs = oldBars.keys.union(newBars.keys)

    if (attrs.isEmpty()) {
        return SmoothedSpearmanResult(
            distance = 0.0,
            threshold = threshold
        )
    }

    val worstDistance = attrs.maxOf { attr ->
        val oldHist = oldBars[attr]
            ?.toList()
            ?.map { it.toDouble() }
            ?: List(N_BINS) { 0.0 }

        val newHist = newBars[attr]
            ?.toList()
            ?.map { it.toDouble() }
            ?: List(N_BINS) { 0.0 }

        smoothedSpearmanDistance(
            a = oldHist,
            b = newHist,
            sigma = sigma,
            radius = radius
        )
    }

    return SmoothedSpearmanResult(
        distance = worstDistance,
        threshold = threshold
    )
}
