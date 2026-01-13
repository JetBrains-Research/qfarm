package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq

object EvolutionContext {
    val frontStack = ArrayDeque<ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>>()
}

fun interface PercentileProvider {
    fun value(attributeIndex: Int, percentile: Double): Double
}

data class RuleInitConfig(
    val rightAttrIndex: Int,
    val bounds: Array<DoubleArray>,
    val percentile: PercentileProvider,

    // Fixed attributes: (index) — these are always present & will mutate
    val fixedAttributes: List<Int> = emptyList(),

    // Explicit list of attribute indices to search among for the “additional” attribute
    // If empty, chromosome will use only the fixed attributes.
    val searchAttributes: List<Int> = emptyList()
)

class SortedColumnsPercentileProvider(
    private val sortedColumns: List<DoubleArray>     // sorted ascending
) : PercentileProvider {

    override fun value(attributeIndex: Int, percentile: Double): Double {
        require(attributeIndex in sortedColumns.indices) { "attributeIndex out of range: $attributeIndex" }
        val col = sortedColumns[attributeIndex]
        val n = col.size
        require(n > 0) { "Empty column at index $attributeIndex" }

        // clamp + fast paths
        val p = percentile.coerceIn(0.0, 1.0)
        if (p <= 0.0) return col[0]
        if (p >= 1.0) return col[n - 1]

        // linear interpolation between nearest ranks
        val pos = p * (n - 1)
        val lo = pos.toInt()
        val hi = lo + 1
        val w = pos - lo

        // exact hit -> avoid extra ops
        if (w == 0.0 || hi >= n) return col[lo]

        val a = col[lo]
        val b = col[hi]
        return a + (b - a) * w
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
