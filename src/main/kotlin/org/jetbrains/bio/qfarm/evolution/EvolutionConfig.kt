package org.jetbrains.bio.qfarm.evolution

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import io.jenetics.util.RandomRegistry
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evaluation.scan.ColumnOrders
import org.jetbrains.bio.qfarm.params.StopMode
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.DiscreteColumnInfo

data class EvolutionEnvironment(
    val datasetWithHeader: DatasetWithHeader,
    val columnNames: List<String>,
    val sortedColumns: List<DoubleArray>,
    val bounds: Array<DoubleArray>,
    val percentileProvider: SortedColumnsPercentileProvider,
    val discreteInfo: DiscreteColumnInfo,
    val rightAttrIndex: Int
) {
    /** Per-column row orders for the cheap box scan; each column is computed on first use. */
    val columnOrders: ColumnOrders by lazy { ColumnOrders(datasetWithHeader.data) }
}

/** Count and time of the cheap box scans of the run (reported in the tree.jsonl summary). */
object ScanTotals {
    data class Totals(val scans: Int = 0, val seconds: Double = 0.0)

    private var totals = Totals()

    @Synchronized
    fun add(seconds: Double) {
        totals = Totals(totals.scans + 1, totals.seconds + seconds)
    }

    @Synchronized
    fun snapshot(): Totals = totals

    @Synchronized
    fun reset() {
        totals = Totals()
    }
}

/** Length of one evolution and its early-stop result (reported per node in tree.jsonl). */
data class EvolutionStats(
    val phase: EvolutionPhase,
    val populationSize: Int,
    val maxGenerations: Int,
    /** Generations that ran (< maxGenerations only when the stop criterion ended the evolution). */
    val generations: Long,
    /** Wall-clock duration of the evolution. Evolutions run in parallel, so they compete for CPU. */
    val seconds: Double,
    /** Sum of Jenetics' per-generation evolve durations: engine work only, without stop-criterion logging. */
    val evolveSeconds: Double,
    val stopMode: StopMode,
    /** First generation on which the stop criterion fired; null when it never did or mode is OFF. */
    val stopGeneration: Long?,
    /**
     * Pareto front at [stopGeneration]: the rules an early stop reports. In STOP mode it is the
     * evolution's final front; in LOG_ONLY the evolution went on to maxGenerations.
     */
    val stopFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>? = null
)

data class ScoredFront(
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val scores: DoubleArray,
    /** The evolution that produced [front]; null for fronts not produced by one evolution. */
    val evolution: EvolutionStats? = null
)

object EvolutionContext {
    val frontStack = ArrayDeque<ScoredFront>()
}

/** Per-phase sums over every evolution of the run (reported in the tree.jsonl summary). */
object EvolutionTotals {

    data class PhaseTotals(
        val evolutions: Int = 0,
        val generations: Long = 0,
        val seconds: Double = 0.0,
        val evolveSeconds: Double = 0.0
    )

    private val byPhase = LinkedHashMap<EvolutionPhase, PhaseTotals>()

    @Synchronized
    fun add(stats: EvolutionStats) {
        val t = byPhase[stats.phase] ?: PhaseTotals()
        byPhase[stats.phase] = PhaseTotals(
            evolutions = t.evolutions + 1,
            generations = t.generations + stats.generations,
            seconds = t.seconds + stats.seconds,
            evolveSeconds = t.evolveSeconds + stats.evolveSeconds
        )
    }

    @Synchronized
    fun snapshot(): Map<EvolutionPhase, PhaseTotals> = LinkedHashMap(byPhase)

    @Synchronized
    fun reset() = byPhase.clear()
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
    val all: List<Int> = indices.sorted()
    private val remaining = all.toMutableList()

    @Synchronized
    fun takeRandom(n: Int = all.size): List<Int> {
        if (all.isEmpty() || n <= 0) return emptyList()
        if (remaining.size < n) {
            remaining.clear()
            remaining.addAll(all)
        }

        val rand = RandomRegistry.random()
        val selected = mutableListOf<Int>()

        repeat(n.coerceAtMost(remaining.size)) {
            val idx = rand.nextInt(remaining.size)
            selected += remaining.removeAt(idx)
        }
        return selected
    }
}
