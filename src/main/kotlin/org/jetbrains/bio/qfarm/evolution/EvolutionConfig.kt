package org.jetbrains.bio.qfarm.evolution

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.init.PairwisePercentilePrior
import org.jetbrains.bio.qfarm.core.init.RuleInitMode
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.rand

data class EvolutionEnvironment(
    val datasetWithHeader: DatasetWithHeader,
    val columnNames: List<String>,
    val sortedColumns: List<DoubleArray>,
    val bounds: Array<DoubleArray>,
    val percentileProvider: SortedColumnsPercentileProvider,
    val rightAttrIndex: Int
)

data class ScoredFront(
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val scores: DoubleArray
)

object EvolutionContext {
    val frontStack = ArrayDeque<ScoredFront>()
}

fun interface PercentileProvider {
    fun value(attributeIndex: Int, percentile: Double): Double
}

interface BidirectionalPercentileProvider : PercentileProvider {
    fun percentileOf(attributeIndex: Int, value: Double): Double
}

data class RuleInitConfig(
    val rightAttrIndex: Int,
    val bounds: Array<DoubleArray>,
    val percentile: PercentileProvider,
    val fixedAttributes: List<Int> = emptyList(),
    val searchAttributes: List<Int> = emptyList(),
    val initMode: RuleInitMode = RuleInitMode.PAIRWISE_PERCENTILE,
    val pairwisePrior: PairwisePercentilePrior? = null
)

class SortedColumnsPercentileProvider(
    private val sortedColumns: List<DoubleArray>
) : BidirectionalPercentileProvider {

    override fun value(attributeIndex: Int, percentile: Double): Double {
        require(attributeIndex in sortedColumns.indices)
        val col = sortedColumns[attributeIndex]
        val n = col.size

        val p = percentile.coerceIn(0.0, 1.0)
        if (p <= 0.0) return col[0]
        if (p >= 1.0) return col[n - 1]

        val pos = p * (n - 1)
        val lo = pos.toInt()
        val hi = lo + 1
        val w = pos - lo

        if (w == 0.0 || hi >= n) return col[lo]

        return col[lo] + (col[hi] - col[lo]) * w
    }

    override fun percentileOf(attributeIndex: Int, value: Double): Double {
        require(attributeIndex in sortedColumns.indices)
        val col = sortedColumns[attributeIndex]
        val n = col.size
        require(n > 0)

        if (value <= col[0]) return 0.0
        if (value >= col[n - 1]) return 1.0

        val idx = col.binarySearch(value)

        val insertionPoint = if (idx >= 0) {
            // For duplicates, use the middle of the equal-value block
            var left = idx
            while (left > 0 && col[left - 1] == value) left--

            var right = idx
            while (right + 1 < n && col[right + 1] == value) right++

            (left + right) / 2.0
        } else {
            (-idx - 1).toDouble()
        }

        return insertionPoint / (n - 1)
    }
}

class IndexPool(indices: Set<Int>) {
    val all: List<Int> = indices.toList()
    private val remaining = all.toMutableList()

    @Synchronized
    fun takeRandom(n: Int = all.size): List<Int> {
        if (all.isEmpty() || n <= 0) return emptyList()
        if (remaining.size < n) {
            remaining.clear()
            remaining.addAll(all)
        }

        val selected = mutableListOf<Int>()
        repeat(n.coerceAtMost(remaining.size)) {
            val idx = rand.nextInt(remaining.size)
            selected += remaining.removeAt(idx)
        }
        return selected
    }
}
