package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TopRangeResult(
    val ranges: Map<Int, ClosedFloatingPointRange<Double>>,
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
)

fun topRange(
    attributes: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    currentDataset: DatasetWithHeader = datasetWithHeader,
): TopRangeResult {
    val start = System.nanoTime()
    println("\n$PURPLE🏆SEARCHING FOR THE BEST RANGE OF ${attributes.map {(idx, range) -> columnNames[idx] to range}} ... $RESET")
    require(attributes.isNotEmpty()) { "attributes must not be empty." }

    // Indices we want to optimize (from pairs)
    val targetIndices: Set<Int> = attributes.map { it.first }.toSet()

    val parentFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? =
        EvolutionContext.frontStack.lastOrNull()

    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> = runEvolution(attributes, popSize = hp.popSizeRange, generationCount = hp.maxGenRange, parentFront = parentFront)

    var bestScore = Double.POSITIVE_INFINITY
    var bestGenes: List<AttributeGene> =
        front.firstOrNull()?.genotype()?.chromosome()?.toList() ?: emptyList()

    for (pt in front) {
        val gt: Genotype<AttributeGene> = pt.genotype()
        val lhs = gt[0] as RuleSideChromosome

        val active = lhs.filterNotNull().filter { !it.isDefault }

        // Must match exactly the attributes we asked to optimize
        if (active.size != attributes.size) continue
        if (!active.all { g -> g.attributeIndex in targetIndices }) continue

        data class Bound(val idx: Int, val lo: Double, val hi: Double)
        val bounds: List<Bound> = active.map { g -> Bound(g.attributeIndex, g.lowerBound, g.upperBound) }

        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        for (row in currentDataset.data) {
            val xOk = bounds.all { b ->
                val v = row[b.idx]
                !v.isNaN() && v >= b.lo && v <= b.hi
            }
            val yOk = (row[rightAttrIndex] >= rightGene.lowerBound && row[rightAttrIndex] <= rightGene.upperBound)
            if (xOk) {
                if (yOk) tp++ else fp++
            } else {
                if (yOk) fn++ else tn++
            }
        }

        val denom1 = fp + tn
        val denom2 = fn + tp
        val type1 = if (denom1 > 0) fp.toDouble() / denom1 else 0.0  // FPR
        val type2 = if (denom2 > 0) fn.toDouble() / denom2 else 0.0  // FNR

        val score = when {
            type1 == 0.0 && type2 == 0.0 -> 0.0
            type1 == 0.0 || type2 == 0.0 -> Double.POSITIVE_INFINITY
            else -> abs((max(type1, type2) / min(type1, type2)) - 1.0)
        }

        if (score < bestScore) {
            bestScore = score
            bestGenes = active
        }
    }

    println("$PURPLE[🏁 Pareto front (all) has ${front.size()} solutions]$RESET")

    // If nothing matched, bail out early with an empty result (or throw, your call)
    if (bestGenes.isEmpty()) {
        println("$YELLOW[⚠️ No solutions matched the requested attributes. Returning empty result.]$RESET")
        return TopRangeResult(
            ranges = emptyMap(),
            front = front
        )
    }

    val byIdx = bestGenes.associateBy { it.attributeIndex }

    // Preserve the order of the incoming 'attributes' list
    val bestRangesOrdered: LinkedHashMap<Int, ClosedFloatingPointRange<Double>> = LinkedHashMap()
    for ((attrIdx, _) in attributes) {
        val g = byIdx.getValue(attrIdx)
        bestRangesOrdered[attrIdx] = g.lowerBound.roundTo(4)..g.upperBound.roundTo(4)
    }

    val sortedColumns = computeSortedColumns(currentDataset.data)
    for ((attr, rng) in bestRangesOrdered) {
        val loPct = cumulativePercentage(sortedColumns[attr], rng.start).toInt()
        val hiPct = cumulativePercentage(sortedColumns[attr], rng.endInclusive).toInt()
        println("$attr percentiles: $loPct..$hiPct, and real bounds: ${rng.start}..${rng.endInclusive}")
    }

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("Range finder: elapsed=%.2fs".format(elapsed))

    return TopRangeResult(
        ranges = bestRangesOrdered,
        front = front
    )
}
