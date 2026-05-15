package org.jetbrains.bio.qfarm.statistics.delong

object DeLong {

    /**
     * Compares two correlated ROC AUCs using DeLong's test.
     *
     * @param labels binary labels (0 = negative, 1 = positive)
     * @param scores1 predicted scores from model 1 (parent)
     * @param scores2 predicted scores from model 2 (parent+addition)
     */
    fun compare(
        labels: IntArray,
        scores1: DoubleArray,
        scores2: DoubleArray
    ): DeLongResult {

        require(labels.size == scores1.size) {
            "labels and scores1 must have same length"
        }

        require(labels.size == scores2.size) {
            "labels and scores2 must have same length"
        }

        require(labels.distinct().size == 2) {
            "Only binary classification supported (labels must contain 0 and 1)"
        }

        return DeLongCalculator.compare(labels, scores1, scores2)
    }
}