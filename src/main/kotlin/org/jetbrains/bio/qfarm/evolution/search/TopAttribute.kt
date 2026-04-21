package org.jetbrains.bio.qfarm.evolution.search

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evaluation.averageVerticalDistance
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET

/* -------------------------- Case: NO parent front -------------------------- */

fun groupFrontByAttribute(
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    fixedIndices: Set<Int>
): MutableMap<Int, MutableList<Phenotype<AttributeGene, Vec<DoubleArray>>>> {
    val groups = mutableMapOf<Int, MutableList<Phenotype<AttributeGene, Vec<DoubleArray>>>>()
    for (pt in front) {
        val lhs = pt.genotype()[0] as RuleSideChromosome
        for (gene in lhs) {
            if (gene != null && !gene.isDefault) {
                val idx = gene.attributeIndex
                if (idx !in fixedIndices) {
                    groups.getOrPut(idx) { mutableListOf() }.add(pt)
                }
            }
        }
    }

    return groups
}

fun topAttributeNoParent(
    prefixAttributes: List<Int>,
    childFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    currentDataset: DatasetWithHeader
): Int? {
    val fixedIndices = prefixAttributes.toSet()
    val groups = groupFrontByAttribute(childFront, fixedIndices)

    if (groups.isEmpty()) {
        println("$RED No non-fixed attributes found in the Pareto front. $RESET")
        return null
    }

    val rankedByFreq = groups.entries
        .map { (idx, list) -> idx to list.size }
        .sortedByDescending { it.second }

    println("=== Attribute frequencies in last-gen front (no parent front) ===")
    for ((idx, count) in rankedByFreq) {
        println("%-20s -> %d".format(currentDataset.header[idx], count))
    }

    return rankedByFreq.first().first
}

/* -------------------------- Case: WITH parent front ------------------------ */

fun topAttributeWithParent(
    prefixAttributes: List<Int>,
    childFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    currentDataset: DatasetWithHeader
): Int? {
    val fixedIndices = prefixAttributes.toSet()
    val groups = groupFrontByAttribute(childFront, fixedIndices)

    if (groups.isEmpty()) {
        println("$RED No non-fixed attributes found in the Pareto front. $RESET")
        return null
    }

    val rankedByAvgDistance = groups.entries
        .mapNotNull { (idx, list) ->
            if (list.isEmpty()) null
            else {
                val subFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> = ISeq.of(list)
                val avgDist = averageVerticalDistance(subFront, parentFront)
                idx to avgDist
            }
        }
        .sortedByDescending { it.second }

    println("=== Avg vertical distance to parent front per attribute ===")
    for ((idx, avg) in rankedByAvgDistance) {
        println("%-20s -> avgDist=%.4f".format(currentDataset.header[idx], avg))
    }

    return rankedByAvgDistance.firstOrNull()?.first
        ?: run {
            println("$RED No valid attribute groups found to compare against the parent front. $RESET")
            null
        }
}
