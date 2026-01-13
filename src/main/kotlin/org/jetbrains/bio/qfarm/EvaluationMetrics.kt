package org.jetbrains.bio.qfarm

import io.jenetics.Genotype

fun evaluateRule(
    genotype: Genotype<AttributeGene>,
    currentDataset: DatasetWithHeader = datasetWithHeader
): DoubleArray {
    val data: List<DoubleArray> = currentDataset.data
    val rows = data.size

    val lhs = genotype[0] as RuleSideChromosome
    val nGenes = lhs.length()

    var k = 0
    // 1st pass: count active
    for (i in 0 until nGenes) if (!lhs[i].isDefault) k++

    val idxs = IntArray(k)
    val lows = DoubleArray(k)
    val ups  = DoubleArray(k)

    // 2nd pass: fill SoA arrays
    run {
        var j = 0
        for (i in 0 until nGenes) {
            val g = lhs[i]
            if (!g.isDefault) {
                idxs[j] = g.attributeIndex
                lows[j] = g.lowerBound
                ups[j]  = g.upperBound
                j++
            }
        }
    }

    val rIdx = rightAttrIndex
    val rLo  = rightGene.lowerBound
    val rUp  = rightGene.upperBound

    var supportX  = 0
    var supportXY = 0

    // Main scan: single pass over rows, tight inner loop over active antecedents
    for (r in 0 until rows) {
        val row = data[r]

        // Y first, independent of X
        val yv = row[rIdx]
        val yOk = (yv >= rLo && yv <= rUp)

        // X check with early exit
        var xOk = true
        var j = 0
        while (j < k) {
            val v = row[idxs[j]]
            if (v < lows[j] || v > ups[j]) { xOk = false; break }
            j++
        }
        if (xOk) {
            supportX++
            if (yOk) supportXY++
        }
    }

    // --- metrics ---
    val sX  = supportX.toDouble()
    val sXY = supportXY.toDouble()

    var conf = 0.0
    if (supportX != 0 && supportXY != 0) {
        conf = sXY / sX
    }

    return doubleArrayOf(sX, conf)
}
