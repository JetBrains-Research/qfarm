package org.jetbrains.bio.qfarm.output

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evaluation.ConfusionMetrics
import org.jetbrains.bio.qfarm.evaluation.toPFSeries
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.numericRuleString
import java.io.File

data class FullRuleRow(
    val rule: String,

    // structure
    val numAttributes: Int,

    // base metrics
    val support: Int,
    val confidence: Double,
    val lift: Double,

    val metrics: ConfusionMetrics,

    // node-level stats
    val deltaArea: Double?,
    val totalArea: Double?,
    val deLong: DeLongResult?
)

fun countActiveAttributes(pt: Phenotype<AttributeGene, Vec<DoubleArray>>): Int {
    val lhs = pt.genotype()[0] as RuleSideChromosome
    return lhs.asSequence()
        .count { !it.isDefault }
}


fun extractFullRows(
    front: ScoredFront,
    dataset: DatasetWithHeader,
    nodeMeta: Map<String, Any?>,
    k: Int = 5
): List<FullRuleRow> {

    if (front.front.isEmpty) return emptyList()

    val positiveRate =
        dataset.labels.count { it == 1 }.toDouble() / dataset.labels.size

    val series = toPFSeries(front.front, "Child", dataset)
    val metricsList = series.metrics ?: return emptyList()

    // --------------------------------------------------
    // 1. Build candidates (index + ratio)
    // --------------------------------------------------
    data class Candidate(val idx: Int, val ratio: Double)

    val candidates = metricsList.mapIndexedNotNull { idx, m ->
        if (!m.ratio.isFinite()) return@mapIndexedNotNull null
        Candidate(idx, m.ratio)
    }

    if (candidates.isEmpty()) return emptyList()

    val sorted = candidates.sortedBy { it.ratio }

    // --------------------------------------------------
    // 2. Select indices: min, q1, center, q3, max
    // --------------------------------------------------

    val selectedIdx = mutableSetOf<Int>()

    val n = sorted.size

    // extremes
    selectedIdx += sorted.first().idx
    selectedIdx += sorted.last().idx

    // center (closest to 1)
    val center = sorted.minByOrNull { kotlin.math.abs(it.ratio - 1.0) }
    center?.let { selectedIdx += it.idx }

    // quartiles
    val q1 = sorted[(n * 0.25).toInt().coerceIn(0, n - 1)]
    val q3 = sorted[(n * 0.75).toInt().coerceIn(0, n - 1)]

    selectedIdx += q1.idx
    selectedIdx += q3.idx

    // If we still don't have enough (due to duplicates), fill sequentially
    if (selectedIdx.size < k) {
        for (c in sorted) {
            selectedIdx += c.idx
            if (selectedIdx.size == k) break
        }
    }

    // Final selection (limit to k)
    val finalIdx = selectedIdx.take(k)

    // --------------------------------------------------
    // 3. Build FULL rows only for selected indices
    // --------------------------------------------------
    return finalIdx.map { idx ->

        val pt = front.front[idx]
        val f = pt.fitness().data()

        val support = f[0].toInt()
        val confidence = f[1]

        val lift =
            if (positiveRate > 0.0) confidence / positiveRate else 0.0

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

            metrics = m,

            deltaArea = nodeMeta["deltaArea"]?.toString()?.toDoubleOrNull(),
            totalArea = nodeMeta["totalArea"]?.toString()?.toDoubleOrNull(),
            deLong = deLong
        )
    }
}

fun writeFullTsv(rows: List<FullRuleRow>, file: File) {

    file.bufferedWriter().use { w ->

        w.appendLine(
            listOf(
                "rule","numAttr",
                "support","confidence","lift",
                "TP","FP","TN","FN","type1","type2","ratio",
                "deltaArea","totalArea",
                "pValue","pValueTwoSided","zScore",
                "aucParent","aucChild",
                "varParent","varChild","covariance"
            ).joinToString("\t")
        )

        for (r in rows) {
            w.appendLine(
                listOf(
                    r.rule,
                    r.numAttributes,
                    r.support,
                    r.confidence,
                    r.lift,
                    r.metrics.tp,
                    r.metrics.fp,
                    r.metrics.tn,
                    r.metrics.fn,
                    r.metrics.type1,
                    r.metrics.type2,
                    r.metrics.ratio,
                    r.deltaArea,
                    r.totalArea,
                    r.deLong?.pOneSided,
                    r.deLong?.pTwoSided,
                    r.deLong?.zScore,
                    r.deLong?.auc1,
                    r.deLong?.auc2,
                    r.deLong?.variance1,
                    r.deLong?.variance2,
                    r.deLong?.covariance
                ).joinToString("\t")
            )
        }
    }
}
