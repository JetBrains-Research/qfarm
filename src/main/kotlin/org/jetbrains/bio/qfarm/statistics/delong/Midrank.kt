package org.jetbrains.bio.qfarm.statistics.delong

internal object Midrank {

    /**
     * Computes midranks for an array.
     * Ties receive the average of their rank positions.
     */
    fun compute(values: DoubleArray): DoubleArray {

        val n = values.size
        val indices = values.indices.sortedBy { values[it] }

        val ranks = DoubleArray(n)

        var i = 0
        while (i < n) {

            var j = i
            while (j + 1 < n &&
                values[indices[j]] == values[indices[j + 1]]
            ) {
                j++
            }

            // Midrank for tied group
            val midRank = (i + j + 2) / 2.0  // +1 for 1-based ranks

            for (k in i..j) {
                ranks[indices[k]] = midRank
            }

            i = j + 1
        }

        return ranks
    }
}