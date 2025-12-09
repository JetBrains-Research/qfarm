package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import kotlin.math.ln

private const val LN2 = 0.6931471805599453 // ln(2)


fun evaluateRule(
    genotype: Genotype<AttributeGene>,
    currentDataset: DatasetWithHeader = datasetWithHeader
): DoubleArray {
    val data: List<DoubleArray> = currentDataset.data
    val rows = data.size
    val datasetSizeD = rows.toDouble()

    // Antecedent chromosome & extract only active genes into primitive arrays
    val lhs = genotype[0] as RuleSideChromosome
    val nGenes = lhs.length()

    var k = 0
    // 1st pass: count active
    for (i in 0 until nGenes) if (!lhs[i].isDefault) k++

    // If you *guarantee* at least one active elsewhere, this won’t be 0.
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

    // Hoist RHS index & bounds (avoid recomputing inside the loop)
    val rIdx = rightAttrIndex
    val rLo  = rightGene.lowerBound  // assumes your existing rightGene in scope
    val rUp  = rightGene.upperBound

    var supportX  = 0
    var supportY  = 0
    var supportXY = 0

    // Main scan: single pass over rows, tight inner loop over active antecedents
    for (r in 0 until rows) {
        val row = data[r]

        // Y first (cheap: one compare), independent of X
        val yv = row[rIdx]
        val yOk = (yv >= rLo && yv <= rUp)
        if (yOk) supportY++

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

    // --- metrics (avoid repeated boxing/conversions) ---
    val sX  = supportX.toDouble()
    val sY  = supportY.toDouble()
    val sXY = supportXY.toDouble()

    val totalConditions = k + 1 // k LHS + 1 RHS
    val comprehensibility = totalConditions.toDouble()

    var lift = 0.0
    if (supportX != 0 && supportY != 0 && supportXY != 0) {
        lift = (sXY / (sX * sY)) * datasetSizeD
    }

    var conf = 0.0
    if (supportX != 0 && supportXY != 0) {
        conf = sXY / sX
    }



    return doubleArrayOf(sX, conf)
}
