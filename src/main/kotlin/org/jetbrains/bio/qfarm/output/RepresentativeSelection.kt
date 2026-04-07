package org.jetbrains.bio.qfarm.output

import org.jetbrains.bio.qfarm.evaluation.ConfusionMetrics
import org.jetbrains.bio.qfarm.evaluation.toPFSeries
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.numericRuleString

fun selectRepresentativeIndices(
    front: ScoredFront,
    metricsList: List<ConfusionMetrics>,
    k: Int = 5,
    minConfidence: Double = 0.4
): List<Int> {

    data class Candidate(val idx: Int, val ratio: Double)

    // -------------------------------
    // 1. Filter by confidence only
    // -------------------------------
    val candidates = metricsList.mapIndexedNotNull { idx, m ->
        val ratio = m.ratio
        if (!ratio.isFinite()) return@mapIndexedNotNull null

        val pt = front.front[idx]
        val confidence = pt.fitness().data()[1]

        if (confidence <= minConfidence) return@mapIndexedNotNull null

        Candidate(idx, ratio)
    }

    if (candidates.isEmpty()) return emptyList()

    val sorted = candidates.sortedBy { it.ratio }
    val n = sorted.size

    val selectedIdx = mutableSetOf<Int>()

    // -------------------------------
    // 2. Extremes
    // -------------------------------
    selectedIdx += sorted.first().idx
    selectedIdx += sorted.last().idx

    // -------------------------------
    // 3. Center (closest to 1)
    // -------------------------------
    sorted.minByOrNull { kotlin.math.abs(it.ratio - 1.0) }
        ?.let { selectedIdx += it.idx }

    // -------------------------------
    // 4. Quartiles
    // -------------------------------
    val q1 = sorted[(n * 0.25).toInt().coerceIn(0, n - 1)]
    val q3 = sorted[(n * 0.75).toInt().coerceIn(0, n - 1)]

    selectedIdx += q1.idx
    selectedIdx += q3.idx

    // -------------------------------
    // 5. Fallback fill if needed
    // -------------------------------
    if (selectedIdx.size < k) {
        for (c in sorted) {
            selectedIdx += c.idx
            if (selectedIdx.size == k) break
        }
    }

    // -------------------------------
    // 6. Final trim
    // -------------------------------
    return selectedIdx.take(k)
}

fun extractFullRows(
    front: ScoredFront,
    dataset: DatasetWithHeader,
    nodeMeta: Map<String, Any?>,
    ruleAttributes: List<Int>,
    k: Int = 5
): List<FullRuleRow> {

    if (front.front.isEmpty) return emptyList()

    val positiveRate =
        dataset.labels.count { it == 1 }.toDouble() / dataset.labels.size

    val series = toPFSeries(front.front, "Child", dataset)
    val metricsList = series.metrics ?: return emptyList()

    // -------------------------------
    // 1. Select representatives
    // -------------------------------
    val selectedIdx = selectRepresentativeIndices(front, metricsList, k)

    // -------------------------------
    // 2. Build rows
    // -------------------------------
    return selectedIdx.map { idx ->

        val pt = front.front[idx]
        val f = pt.fitness().data()

        val support = f[0].toInt()
        val confidence = f[1]
        val lift = if (positiveRate > 0.0) confidence / positiveRate else 0.0
        val numAttr = countActiveAttributes(pt)

        val rule = numericRuleString(dataset.header, pt.genotype(), false)

        val m = metricsList.getOrNull(idx) ?: ConfusionMetrics(
            tp = 0, fp = 0, tn = 0, fn = 0,
            type1 = Double.NaN,
            type2 = Double.NaN,
            ratio = Double.NaN
        )

        val deLong = nodeMeta["deLong"] as? DeLongResult

        FullRuleRow(
            rule = rule,
            numAttributes = numAttr,
            support = support,
            confidence = confidence,
            lift = lift,
            confusionMetrics = m,
            deltaArea = nodeMeta["deltaArea"]?.toString()?.toDoubleOrNull(),
            totalArea = nodeMeta["totalArea"]?.toString()?.toDoubleOrNull(),
            deLong = deLong,
            ruleAttributes = ruleAttributes
        )
    }
}
