package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq


fun topAttribute(
    prefixAttributes: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    searchAttributes: List<Int>,
    currentDataset: DatasetWithHeader = datasetWithHeader,
): Int? {
    val start = System.nanoTime()
    println("\n$BLUE\uD83E\uDD47 SEARCHING FOR THE BEST ATTRIBUTE ... $RESET")
    require(searchAttributes.isNotEmpty()) { "searchAttributes must not be empty." }

    val parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        EvolutionContext.frontStack.lastOrNull() ?: ISeq.empty()
    val hasParent = !parentFront.isEmpty

    val (popSize, maxGen) =
        if (hasParent) hp.popSizeAttrParent to hp.maxGenAttrParent   // case: WITH parent
        else hp.popSizeAttrFirst to hp.maxGenAttrFirst   // case: NO parent

    val randomFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> =
        runEvolution(prefixAttributes, searchAttributes, popSize, maxGen, parentFront)

    val result = if (!hasParent) {
        topAttributeNoParent(prefixAttributes, randomFront, currentDataset)
    } else {
        topAttributeWithParent(prefixAttributes, randomFront, parentFront, currentDataset)
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("Best avg: elapsed=%.2fs".format(elapsed))

    return result
}


/* -------------------------- Case: NO parent front -------------------------- */

private fun groupFrontByAttribute(
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

private fun topAttributeNoParent(
    prefixAttributes: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    childFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    currentDataset: DatasetWithHeader
): Int? {
    val fixedIndices = prefixAttributes.map { it.first }.toSet()
    val groups = groupFrontByAttribute(childFront, fixedIndices)

    if (groups.isEmpty()) {
        println("$RED No non-fixed attributes found in the Pareto front.$RESET")
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

private fun topAttributeWithParent(
    prefixAttributes: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    childFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    currentDataset: DatasetWithHeader
): Int? {
    val fixedIndices = prefixAttributes.map { it.first }.toSet()
    val groups = groupFrontByAttribute(childFront, fixedIndices)

    if (groups.isEmpty()) {
        println("$RED No non-fixed attributes found in the Pareto front.$RESET")
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
            println("$RED No valid attribute groups found to compare against the parent front.$RESET")
            null
        }
}
