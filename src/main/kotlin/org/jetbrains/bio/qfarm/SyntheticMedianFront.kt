package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.statistics.delong.AUC
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

object MedianFront {
    lateinit var scoredFront: ScoredFront
    lateinit var datasetWithHeader: DatasetWithHeader
    var auc: Double = Double.NaN
    var initialized: Boolean = false
}

data class SyntheticResult(
    val auc: Double,
    val front: ScoredFront,
    val dataset: DatasetWithHeader
)

fun generateMedianFront(
    nColumns: Int = 50   // 50 is enough in practice
) {
    if (MedianFront.initialized) return

    val globalStart = System.nanoTime()

    println("Generating median front (parallel)...")

    val baseData = datasetWithHeader.data
    val labels = datasetWithHeader.labels
    val nRows = baseData.size

    val rhsIndex = rightAttrIndex
    val rhsColumn = baseData.map { it[rhsIndex] }

    val rhsSorted = sortedColumns[rhsIndex]
    val rhsBounds = bounds[rhsIndex]

    val nThreads = Runtime.getRuntime().availableProcessors()
    val executor = Executors.newFixedThreadPool(nThreads)

    val tasks = (0 until nColumns).map { colIdx ->
        Callable<SyntheticResult?> {

            val start = System.nanoTime()
            val rng = ThreadLocalRandom.current()

            println("[Baseline] Column ${colIdx + 1}/$nColumns START")

            // --- 1. synthetic column ---
            val column = DoubleArray(nRows) { rng.nextDouble() }

            // --- 2. sorted + bounds (cheap) ---
            val synSorted = column.copyOf().apply { sort() }

            val localSortedColumns = listOf(synSorted, rhsSorted)
            val localBounds = arrayOf(
                doubleArrayOf(synSorted.first(), synSorted.last()),
                rhsBounds
            )

            val percentileProvider = SortedColumnsPercentileProvider(localSortedColumns)

            // --- 3. dataset ---
            val data = ArrayList<DoubleArray>(nRows)
            for (i in 0 until nRows) {
                data.add(doubleArrayOf(column[i], rhsColumn[i]))
            }

            val header = listOf("Median", columnNames[rhsIndex])

            val localDataset = DatasetWithHeader(
                header = header,
                data = data,
                labels = labels
            )

            val syntheticEnv = EvolutionEnvironment(
                datasetWithHeader = localDataset,
                columnNames = header,
                sortedColumns = localSortedColumns,
                bounds = localBounds,
                percentileProvider = percentileProvider,
                rightAttrIndex = 1
            )

            // ⚠️ still global — ideally remove later
            EvolutionContext.frontStack.clear()

            // --- 4. evolution ---
            val scoredFront = topRange(listOf(0), env = syntheticEnv)

            if (scoredFront.scores.isEmpty()) {
                println("[Baseline] Column ${colIdx + 1} skipped (empty front)")
                return@Callable null
            }

            val auc = AUC.compute(labels, scoredFront.scores)

            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0

            println(
                "[Baseline] Column ${colIdx + 1} DONE | AUC=%.4f | time=%.2fs"
                    .format(auc, elapsed)
            )

            SyntheticResult(auc, scoredFront, localDataset)
        }
    }

    val results = executor.invokeAll(tasks)
        .mapNotNull { it.get() }

    executor.shutdown()

    require(results.isNotEmpty()) { "No valid synthetic fronts generated." }

    // --- 5. median selection ---
    val sorted = results.sortedBy { it.auc }
    val median = sorted[sorted.size / 2]

    MedianFront.scoredFront = median.front
    MedianFront.datasetWithHeader = median.dataset   // ✅ key
    MedianFront.auc = median.auc
    MedianFront.initialized = true

    val totalElapsed = (System.nanoTime() - globalStart) / 1_000_000_000.0

    println("Median front ready: median AUC = %.4f".format(median.auc))
    println("TOTAL median-front time: %.2fs".format(totalElapsed))
}
