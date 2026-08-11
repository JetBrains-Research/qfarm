package org.jetbrains.bio.qfarm.evaluation.fronts

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.GLOBAL_ENV
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment

fun computeFrontScores(
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    env: EvolutionEnvironment = GLOBAL_ENV
): DoubleArray {

    val data = env.datasetWithHeader.data
    val rows = data.size

    val counts = IntArray(rows)

    for (pt in front) {

        val genotype = pt.genotype()
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

        for (r in data.indices) {

            val eps = 1e-6

            val row = data[r]

            var covered = true
            var j = 0

            while (j < k) {
                val v = row[idxs[j]]

                if (v.isNaN()) {
                    error("NaN encountered in dataset at row=$r column=${idxs[j]}. NaNs not supported yet.")
                }

                if (v < lows[j] - eps || v > ups[j] + eps) {
                    covered = false
                    break
                }

                j++
            }

            if (covered) counts[r]++
        }
    }

    val scores = DoubleArray(rows)
    for (i in 0 until rows) {
        scores[i] = counts[i].toDouble()
    }

    return scores
}
