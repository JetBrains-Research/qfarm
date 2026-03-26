package org.jetbrains.bio.qfarm.evaluation

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

data class PFSeries(
    val name: String,
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val metrics: Map<String, List<*>>? = null,
    val bestIndex: Int? = null          // optional "best" per series
)

fun toPFSeries(
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?,
    seriesName: String,
    newDataset: DatasetWithHeader = datasetWithHeader,
): PFSeries {

    val data = newDataset.data

    var bestScore = Double.POSITIVE_INFINITY
    var bestIdx   = -1

    val tpList = mutableListOf<Int>()
    val fpList = mutableListOf<Int>()
    val tnList = mutableListOf<Int>()
    val fnList = mutableListOf<Int>()
    val type1List = mutableListOf<Double>()
    val type2List = mutableListOf<Double>()
    val ratioList = mutableListOf<Double>()

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

        val score = when {
            type1 == 0.0 && type2 == 0.0 -> 0.0
            type1 == 0.0 || type2 == 0.0 -> Double.POSITIVE_INFINITY
            else -> abs((max(type1, type2) / min(type1, type2)) - 1.0)
        }

        tpList += tp; fpList += fp; tnList += tn; fnList += fn
        type1List += type1; type2List += type2
        ratioList += if (type2 > 0.0) type1 / type2 else Double.POSITIVE_INFINITY

        if (score < bestScore) { bestScore = score; bestIdx = i }
    }

    val metrics = mapOf(
        "TP" to tpList,
        "FP" to fpList,
        "TN" to tnList,
        "FN" to fnList,
        "type1" to type1List,
        "type2" to type2List,
        "ratio" to ratioList
    )

    return PFSeries(seriesName, front, metrics, if (bestIdx >= 0) bestIdx else null)
}
