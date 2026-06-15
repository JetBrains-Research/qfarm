package org.jetbrains.bio.qfarm.core.init

import org.jetbrains.bio.qfarm.evolution.BidirectionalPercentileProvider

fun buildPairwisePercentilePrior(
    dataset: List<DoubleArray>,
    percentile: BidirectionalPercentileProvider,
    attributeCount: Int,
    binCount: Int = 10
): PairwisePercentilePrior {
    val n = dataset.size

    val binByRowAttr = Array(n) { IntArray(attributeCount) }

    for (rowIndex in dataset.indices) {
        val row = dataset[rowIndex]

        for (attr in 0 until attributeCount) {
            val p = percentile.percentileOf(attr, row[attr])
            val bin = (p * binCount).toInt().coerceIn(0, binCount - 1)

            binByRowAttr[rowIndex][attr] = bin
        }
    }

    val marginal = Array(attributeCount) { IntArray(binCount) }

    for (row in 0 until n) {
        for (attr in 0 until attributeCount) {
            marginal[attr][binByRowAttr[row][attr]]++
        }
    }

    val corrections =
        Array(attributeCount) {
            Array(attributeCount) {
                Array(binCount) {
                    DoubleArray(binCount) { 1.0 }
                }
            }
        }

    val eps = 1e-9

    for (i in 0 until attributeCount) {
        for (j in i + 1 until attributeCount) {
            val joint = Array(binCount) { IntArray(binCount) }

            for (row in 0 until n) {
                val bi = binByRowAttr[row][i]
                val bj = binByRowAttr[row][j]
                joint[bi][bj]++
            }

            for (bi in 0 until binCount) {
                for (bj in 0 until binCount) {
                    val pJoint = joint[bi][bj].toDouble() / n
                    val pI = marginal[i][bi].toDouble() / n
                    val pJ = marginal[j][bj].toDouble() / n

                    val cij = (pJoint + eps) / ((pI + eps) * (pJ + eps))

                    corrections[i][j][bi][bj] =
                        cij.coerceIn(0.25, 4.0)
                }
            }
        }
    }

    return PairwisePercentilePrior(binCount, corrections)
}
