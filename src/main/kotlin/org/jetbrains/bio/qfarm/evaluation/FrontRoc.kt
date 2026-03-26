package org.jetbrains.bio.qfarm.evaluation

data class ROCPoint(val fpr: Double, val tpr: Double)

fun computeROC(labels: IntArray, scores: DoubleArray): List<ROCPoint> {
    require(labels.size == scores.size)

    val pairs = labels.indices
        .map { i -> scores[i] to labels[i] }
        .sortedByDescending { it.first }

    val P = labels.count { it == 1 }.toDouble()
    val N = labels.count { it == 0 }.toDouble()

    var tp = 0.0
    var fp = 0.0

    val roc = mutableListOf<ROCPoint>()
    roc += ROCPoint(0.0, 0.0)

    for ((_, label) in pairs) {
        if (label == 1) tp++ else fp++

        val tpr = if (P > 0) tp / P else 0.0
        val fpr = if (N > 0) fp / N else 0.0

        roc += ROCPoint(fpr, tpr)
    }

    roc += ROCPoint(1.0, 1.0)
    return roc
}
