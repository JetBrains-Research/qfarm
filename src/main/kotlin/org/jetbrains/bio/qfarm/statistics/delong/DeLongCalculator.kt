package org.jetbrains.bio.qfarm.statistics.delong

import org.apache.commons.math3.distribution.NormalDistribution
import kotlin.math.abs
import kotlin.math.sqrt

internal object DeLongCalculator {

    fun compare(
        labels: IntArray,
        scores1: DoubleArray,
        scores2: DoubleArray
    ): DeLongResult {

        require(labels.size == scores1.size) { "labels and scores1 must have same length" }
        require(labels.size == scores2.size) { "labels and scores2 must have same length" }

        require(labels.any { it == 1 } && labels.any { it == 0 }) {
            "Both positive and negative labels required"
        }

        require(scores1.none { it.isNaN() }) { "scores1 contains NaN" }
        require(scores2.none { it.isNaN() }) { "scores2 contains NaN" }
        require(labels.all { it == 0 || it == 1 }) {
            "Labels must be binary (0 or 1)"
        }

        val positives = labels.count { it == 1 }
        val negatives = labels.count { it == 0 }

        require(positives >= 2) {
            "At least 2 positive samples required for DeLong test"
        }

        require(negatives >= 2) {
            "At least 2 negative samples required for DeLong test"
        }

        // --- Step 1: Split positives and negatives once ---
        val positives1 = ArrayList<Double>()
        val negatives1 = ArrayList<Double>()

        val positives2 = ArrayList<Double>()
        val negatives2 = ArrayList<Double>()

        for (i in labels.indices) {
            if (labels[i] == 1) {
                positives1.add(scores1[i])
                positives2.add(scores2[i])
            } else {
                negatives1.add(scores1[i])
                negatives2.add(scores2[i])
            }
        }

        require(positives1.isNotEmpty() && negatives1.isNotEmpty()) {
            "Need at least one positive and one negative sample"
        }

        val m = positives1.size
        val n = negatives1.size

        // --- Step 2: Compute AUCs using existing utility ---
        val auc1 = AUC.compute(labels, scores1)
        val auc2 = AUC.compute(labels, scores2)

        // --- Step 3: Compute contributions ---
        val (v10_1, v01_1) = computeContributionsFast(labels, scores1)
        val (v10_2, v01_2) = computeContributionsFast(labels, scores2)

        // --- Step 4: Compute variances ---
        val var1 =
            variance(v10_1) / m +
                    variance(v01_1) / n

        val var2 =
            variance(v10_2) / m +
                    variance(v01_2) / n

        // --- Step 5: Compute covariance ---
        val cov =
            covariance(v10_1, v10_2) / m +
                    covariance(v01_1, v01_2) / n

        // --- Step 6: Compute z-score ---
        val denominator = sqrt(var1 + var2 - 2 * cov)

        val deltaAuc = auc2 - auc1

        val zEffect = if (denominator == 0.0) {
            0.0
        } else {
            deltaAuc / denominator
        }

        val normal = NormalDistribution()

        val pTwoSided = if (denominator == 0.0) {
            1.0
        } else {
            // TODO: see both one-sided ..., 2min(.,.)
            2 * normal.cumulativeProbability(-abs(zEffect))
        }

        // One-sided: H1: AUC2 > AUC1
        val pOneSided = if (denominator == 0.0) {
            1.0
        } else {
            normal.cumulativeProbability(-zEffect)
        }

        return DeLongResult(
            auc1 = auc1,
            auc2 = auc2,
            variance1 = var1,
            variance2 = var2,
            covariance = cov,
            zScore = zEffect,
            pTwoSided = pTwoSided,
            pOneSided = pOneSided
        )
    }

    private fun computeContributionsFast(
        labels: IntArray,
        scores: DoubleArray
    ): Pair<DoubleArray, DoubleArray> {

        val n = scores.size

        val ranksAll = Midrank.compute(scores)

        val positiveScores = ArrayList<Double>()
        val negativeScores = ArrayList<Double>()

        val positiveIndices = ArrayList<Int>()
        val negativeIndices = ArrayList<Int>()

        for (i in 0 until n) {
            if (labels[i] == 1) {
                positiveScores.add(scores[i])
                positiveIndices.add(i)
            } else {
                negativeScores.add(scores[i])
                negativeIndices.add(i)
            }
        }

        val m = positiveScores.size
        val nNeg = negativeScores.size

        val ranksPos = Midrank.compute(positiveScores.toDoubleArray())
        val ranksNeg = Midrank.compute(negativeScores.toDoubleArray())

        val v10 = DoubleArray(m)
        val v01 = DoubleArray(nNeg)

        // V10
        for (i in 0 until m) {
            val globalRank = ranksAll[positiveIndices[i]]
            val localRank = ranksPos[i]
            v10[i] = (globalRank - localRank) / nNeg
        }

        // V01
        for (j in 0 until nNeg) {
            val globalRank = ranksAll[negativeIndices[j]]
            val localRank = ranksNeg[j]
            v01[j] = 1.0 - (globalRank - localRank) / m
        }

        return Pair(v10, v01)
    }

    private fun variance(values: DoubleArray): Double {
        val mean = values.average()
        var sum = 0.0
        for (v in values) {
            sum += (v - mean) * (v - mean)
        }
        return sum / (values.size - 1) // sample variance
    }

    private fun covariance(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size)

        val meanA = a.average()
        val meanB = b.average()

        var sum = 0.0
        for (i in a.indices) {
            sum += (a[i] - meanA) * (b[i] - meanB)
        }

        return sum / (a.size - 1) // sample covariance
    }


}