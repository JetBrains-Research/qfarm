package org.jetbrains.bio.qfarm.output.rules

import org.jetbrains.bio.qfarm.evaluation.fronts.ConfusionMetrics
import org.jetbrains.bio.qfarm.evaluation.fronts.toPFSeries
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.numericRuleString
import kotlin.math.abs

fun selectRepresentativeIndices(
    front: ScoredFront,
    metricsList: List<ConfusionMetrics>,
    k: Int = 5,
    minConfidence: Double = 0.3
): List<Int> {

    data class Candidate(val idx: Int, val ratio: Double)

    // -------------------------------
    // 1. Filter by confidence
    // -------------------------------
    val candidates = metricsList.mapIndexedNotNull { idx, m ->
        val ratio = m.ratio
        if (!ratio.isFinite()) return@mapIndexedNotNull null

        val confidence = front.front[idx].fitness().data()[1]
        if (confidence <= minConfidence) return@mapIndexedNotNull null

        Candidate(idx, ratio)
    }

    if (candidates.isEmpty()) return emptyList()

    val sorted = candidates.sortedBy { it.ratio }

    val left = sorted.filter { it.ratio < 1.0 }
    val right = sorted.filter { it.ratio > 1.0 }

    val selected = mutableListOf<Int>()

    // -------------------------------
    // 2. Always include best center
    // -------------------------------
    sorted.minByOrNull { abs(it.ratio - 1.0) }
        ?.let { selected += it.idx }

    // -------------------------------
    // 3. LEFT side (priority)
    // -------------------------------
    if (left.isNotEmpty()) {
        val nL = left.size

        // left extreme (but not too extreme)
        selected += left.first().idx

        // left quartile (~0.25)
        selected += left[(nL * 0.25).toInt().coerceIn(0, nL - 1)].idx

        // left near center (~0.75)
        selected += left[(nL * 0.75).toInt().coerceIn(0, nL - 1)].idx
    }

    // -------------------------------
    // 4. RIGHT side (only if needed)
    // -------------------------------
    if (selected.size < k && right.isNotEmpty()) {
        val nR = right.size

        // pick something close to center, not extreme
        val r = right[(nR * 0.25).toInt().coerceIn(0, nR - 1)]
        selected += r.idx
    }

    // -------------------------------
    // 5. Deduplicate + fill
    // -------------------------------
    val final = linkedSetOf<Int>()
    final.addAll(selected)

    if (final.size < k) {
        for (c in sorted) {
            final += c.idx
            if (final.size == k) break
        }
    }

    return final.take(k)
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
