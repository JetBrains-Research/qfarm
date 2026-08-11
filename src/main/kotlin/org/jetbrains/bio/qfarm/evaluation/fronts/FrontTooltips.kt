package org.jetbrains.bio.qfarm.evaluation.fronts

import io.jenetics.Genotype
import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.datasetWithHeader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ConfusionMetrics(
    val tp: Int,
    val fp: Int,
    val tn: Int,
    val fn: Int,

    val type1: Double,
    val type2: Double,
    val ratio: Double
)

data class PFSeries(
    val name: String,
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val metrics: List<ConfusionMetrics>? = null,
    val bestIndex: Int? = null
)

fun toPFSeries(
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    seriesName: String,
    newDataset: DatasetWithHeader = datasetWithHeader,
): PFSeries {

    val data = newDataset.data

    var bestScore = Double.POSITIVE_INFINITY
    var bestIdx   = -1

    val metricsList = mutableListOf<ConfusionMetrics>()

    for ((i, pt) in front!!.withIndex()) {
        val gt: Genotype<AttributeGene> = pt.genotype()
        val lhs = gt[0] as RuleSideChromosome

        val active = lhs.filterNotNull().filter { !it.isDefault }

        data class Bound(val idx: Int, val lo: Double, val hi: Double)
        val bounds = active.map { g -> Bound(g.attributeIndex, g.lowerBound, g.upperBound) }

        var tp = 0
        var fp = 0
        var fn = 0
        var tn = 0

        val labels = newDataset.labels

        for (rowIdx in data.indices) {
            val row = data[rowIdx]

            val yOk = labels[rowIdx] == 1

            // LHS NaN check
            var lhsMissing = false
            for (b in bounds) {
                if (row[b.idx].isNaN()) {
                    lhsMissing = true
                    break
                }
            }
            if (lhsMissing) continue

            val xOk = bounds.all { b ->
                val v = row[b.idx]
                v >= b.lo && v <= b.hi
            }

            if (xOk) {
                if (yOk) tp++ else fp++
            } else {
                if (yOk) fn++ else tn++
            }
        }

        val denom1 = fp + tn
        val denom2 = fn + tp

        val type1 = if (denom1 > 0) fp.toDouble() / denom1 else 0.0
        val type2 = if (denom2 > 0) fn.toDouble() / denom2 else 0.0

        val ratio =
            if (type2 > 0.0) type1 / type2 else Double.POSITIVE_INFINITY

        val score = when {
            type1 == 0.0 && type2 == 0.0 -> 0.0
            type1 == 0.0 || type2 == 0.0 -> Double.POSITIVE_INFINITY
            else -> abs((max(type1, type2) / min(type1, type2)) - 1.0)
        }

        metricsList += ConfusionMetrics(
            tp = tp,
            fp = fp,
            tn = tn,
            fn = fn,
            type1 = type1,
            type2 = type2,
            ratio = ratio
        )

        if (score < bestScore) {
            bestScore = score
            bestIdx = i
        }
    }

    return PFSeries(
        name = seriesName,
        front = front,
        metrics = metricsList,
        bestIndex = if (bestIdx >= 0) bestIdx else null
    )
}
