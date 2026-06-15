package org.jetbrains.bio.qfarm.core.init

import org.jetbrains.bio.qfarm.rand
import kotlin.math.*

const val MIN_WIDTH = 1e-4

fun sampleTargetSupportRatio(
    minSupport: Int,
    maxSupport: Int,
    datasetSize: Int
): Double {
    val rMin = minSupport.toDouble() / datasetSize
    val rMax = maxSupport.toDouble() / datasetSize

    val a = ln(rMin)
    val b = ln(rMax)
    val mu = (a + b) / 2.0

    // roughly concentrated around the interval
    val sigma = (b - a) / 4.0

    return exp(mu + rand.nextGaussian() * sigma)
        .coerceIn(rMin, rMax)
}

fun sampleWidthsWithProduct(
    k: Int,
    productTarget: Double,
    maxWidth: Double,
    centers: List<Double>? = null,
    maxAttempts: Int = 100
): List<Double>? {
    require(k >= 1)

    repeat(maxAttempts) {
        val base = ln(productTarget) / k

        val eps = DoubleArray(k) {
            rand.nextGaussian() * 0.35
        }

        val mean = eps.average()

        val widths = List(k) { i ->
            exp(base + eps[i] - mean)
        }

        val valid = widths.withIndex().all { (i, w) ->
            val centerOk = centers?.let { c ->
                w <= 2.0 * min(c[i], 1.0 - c[i])
            } ?: true

            w in MIN_WIDTH..maxWidth && centerOk
        }

        if (valid) return widths
    }

    return null
}
