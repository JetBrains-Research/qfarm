package org.jetbrains.bio.qfarm.core.init

enum class RuleInitMode {
    ORIGINAL_RANDOM,       // current behavior
    INDEPENDENT,           // product(widths) = R
    PAIRWISE_PERCENTILE    // product(widths) = R / C
}

class PairwisePercentilePrior(
    val binCount: Int,
    private val corrections: Array<Array<Array<DoubleArray>>>
) {
    fun binOf(center: Double): Int =
        (center * binCount)
            .toInt()
            .coerceIn(0, binCount - 1)

    fun correction(attrI: Int, attrJ: Int, binI: Int, binJ: Int): Double {
        return if (attrI < attrJ) {
            corrections[attrI][attrJ][binI][binJ]
        } else {
            corrections[attrJ][attrI][binJ][binI]
        }
    }
}
