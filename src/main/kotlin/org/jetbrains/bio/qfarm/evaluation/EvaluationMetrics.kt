package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Genotype
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.datasetWithHeader

fun evaluateRule(
    genotype: Genotype<AttributeGene>,
    currentDataset: DatasetWithHeader = datasetWithHeader
): DoubleArray {

    val data = currentDataset.data
    val labels = currentDataset.labels

    val lhs = genotype[0] as RuleSideChromosome
    val nGenes = lhs.length()

    var k = 0
    for (i in 0 until nGenes) if (!lhs[i].isDefault) k++

    val idxs = IntArray(k)
    val lows = DoubleArray(k)
    val ups  = DoubleArray(k)

    var jFill = 0
    for (i in 0 until nGenes) {
        val g = lhs[i]
        if (!g.isDefault) {
            idxs[jFill] = g.attributeIndex
            lows[jFill] = g.lowerBound
            ups[jFill]  = g.upperBound
            jFill++
        }
    }

    var supportX  = 0
    var supportXY = 0

    for (r in data.indices) {

        val row = data[r]

        var xOk = true
        var j = 0
        while (j < k) {
            val v = row[idxs[j]]
            if (v.isNaN() || v < lows[j] || v > ups[j]) {
                xOk = false
                break
            }
            j++
        }

        if (!xOk) continue

        supportX++
        if (labels[r] == 1) supportXY++
    }

    val sX = supportX.toDouble()

    val conf = if (supportX != 0) {
        supportXY.toDouble() / sX
    } else 0.0

    return doubleArrayOf(sX, conf)
}
