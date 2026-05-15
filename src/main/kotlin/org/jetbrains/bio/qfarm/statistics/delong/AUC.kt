package org.jetbrains.bio.qfarm.statistics.delong

internal object AUC {

    /**
     * Fast AUC computation using rank identity.
     */
    fun compute(
        labels: IntArray,
        scores: DoubleArray
    ): Double {

        require(labels.size == scores.size)

        val n = scores.size

        val ranks = Midrank.compute(scores)

        var sumPositiveRanks = 0.0
        var m = 0
        var neg = 0

        for (i in 0 until n) {
            if (labels[i] == 1) {
                sumPositiveRanks += ranks[i]
                m++
            } else {
                neg++
            }
        }

        require(m > 0 && neg > 0)

        val nNeg = neg.toDouble()
        val mPos = m.toDouble()

        return (sumPositiveRanks - mPos * (mPos + 1) / 2.0) / (mPos * nNeg)
    }
}