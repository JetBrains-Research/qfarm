package org.jetbrains.bio.qfarm.evaluation.fronts

import kotlin.collections.iterator

data class ROCPoint(val fpr: Double, val tpr: Double)

fun computeROC(labels: IntArray, scores: DoubleArray): List<ROCPoint> {
    require(labels.size == scores.size) {
        "labels and scores must have same size"
    }

    val positives = labels.count { it == 1 }.toDouble()
    val negatives = labels.count { it == 0 }.toDouble()

    require(positives > 0.0) { "ROC requires at least one positive label." }
    require(negatives > 0.0) { "ROC requires at least one negative label." }

    val groups = labels.indices
        .groupBy { scores[it] }
        .toSortedMap(compareByDescending { it })

    var tp = 0.0
    var fp = 0.0

    val roc = mutableListOf<ROCPoint>()
    roc += ROCPoint(0.0, 0.0)

    for ((_, idxs) in groups) {
        for (i in idxs) {
            if (labels[i] == 1) tp++ else fp++
        }

        roc += ROCPoint(
            fpr = fp / negatives,
            tpr = tp / positives
        )
    }

    return roc
}
