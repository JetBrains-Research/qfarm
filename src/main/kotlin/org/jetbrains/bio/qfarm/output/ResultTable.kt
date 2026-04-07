package org.jetbrains.bio.qfarm.output

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evaluation.toPFSeries
import org.jetbrains.bio.qfarm.evolution.ScoredFront
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

    // confusion matrix
    val tp: Int,
    val fp: Int,
    val tn: Int,
    val fn: Int,
    val ratio: Double,

    // node-level stats
    val deltaArea: Double?,
    val totalArea: Double?,
    val pValue: Double?,
    val pValueTwoSided: Double?,
    val zScore: Double?,
    val aucParent: Double?,
    val aucChild: Double?,
    val varianceParent: Double?,
    val varianceChild: Double?,
    val covariance: Double?
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
    val metrics = series.metrics ?: return emptyList()

    val tpList = metrics.tp
    val fpList = metrics.fp
    val tnList = metrics.tn
    val fnList = metrics.fn
    val ratioList = metrics.ratio

    // --------------------------------------------------
    // 1. Build candidates (index + ratio)
    // --------------------------------------------------
    data class Candidate(val idx: Int, val ratio: Double)

    val candidates = ratioList.mapIndexedNotNull { idx, ratio ->
        if (!ratio.isFinite()) return@mapIndexedNotNull null
        Candidate(idx, ratio)
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

        FullRuleRow(
            rule = rule,
            numAttributes = numAttr,

            support = support,
            confidence = confidence,
            lift = lift,

            tp = tpList[idx],
            fp = fpList[idx],
            tn = tnList[idx],
            fn = fnList[idx],
            ratio = ratioList[idx],

            deltaArea = nodeMeta["deltaArea"]?.toString()?.toDoubleOrNull(),
            totalArea = nodeMeta["totalArea"]?.toString()?.toDoubleOrNull(),
            pValue = nodeMeta["pValue"]?.toString()?.toDoubleOrNull(),
            pValueTwoSided = nodeMeta["pValueTwoSided"]?.toString()?.toDoubleOrNull(),
            zScore = nodeMeta["zScore"]?.toString()?.toDoubleOrNull(),
            aucParent = nodeMeta["aucParent"]?.toString()?.toDoubleOrNull(),
            aucChild = nodeMeta["aucChild"]?.toString()?.toDoubleOrNull(),
            varianceParent = nodeMeta["varianceParent"]?.toString()?.toDoubleOrNull(),
            varianceChild = nodeMeta["varianceChild"]?.toString()?.toDoubleOrNull(),
            covariance = nodeMeta["covariance"]?.toString()?.toDoubleOrNull()
        )
    }
}

fun writeFullTsv(rows: List<FullRuleRow>, file: File) {

    file.bufferedWriter().use { w ->

        w.appendLine(
            listOf(
                "rule","numAttr",
                "support","confidence","lift",
                "TP","FP","TN","FN","ratio",
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
                    r.tp,
                    r.fp,
                    r.tn,
                    r.fn,
                    r.ratio,
                    r.deltaArea,
                    r.totalArea,
                    r.pValue,
                    r.pValueTwoSided,
                    r.zScore,
                    r.aucParent,
                    r.aucChild,
                    r.varianceParent,
                    r.varianceChild,
                    r.covariance
                ).joinToString("\t")
            )
        }
    }
}
