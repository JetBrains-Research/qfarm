package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.statistics.delong.AUC
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

object MedianFront {
    lateinit var scoredFront: ScoredFront
    var auc: Double = Double.NaN
    var initialized: Boolean = false
}


fun generateMedianFront(
    nColumns: Int = 100
) {
    if (MedianFront.initialized) return

    val globalStart = System.nanoTime()

    println("Generating median front (parallel)...")

    val dataset = datasetWithHeader.data
    val nRows = dataset.size
    val labels = datasetWithHeader.labels

    val rhsIndex = rightAttrIndex
    val rhsColumn = dataset.map { it[rhsIndex] }

    val rhsSorted = sortedColumns[rhsIndex]
    val rhsBounds = bounds[rhsIndex]

    val nThreads = Runtime.getRuntime().availableProcessors()
    val executor = Executors.newFixedThreadPool(nThreads)

    val tasks = (0 until nColumns).map { colIdx ->
        Callable {

            val start = System.nanoTime()

            val rng = ThreadLocalRandom.current()

            println("[Baseline] Column ${colIdx + 1}/$nColumns START")

            val column = DoubleArray(nRows) { rng.nextDouble() }

            val synSorted = column.copyOf().apply { sort() }

            val sortedColumns = listOf(
                synSorted,
                rhsSorted
            )

            val bounds = arrayOf(
                doubleArrayOf(synSorted.first(), synSorted.last()),
                rhsBounds
            )

            val percentileProvider = SortedColumnsPercentileProvider(sortedColumns)

            val data = List(nRows) { i ->
                doubleArrayOf(column[i], rhsColumn[i])
            }

            val header = listOf("SYN$colIdx", columnNames[rhsIndex])

            val datasetWithHeader = DatasetWithHeader(
                header = header,
                data = data,
                labels = labels
            )

            val syntheticEnv = EvolutionEnvironment(
                datasetWithHeader = datasetWithHeader,
                columnNames = header,
                sortedColumns = sortedColumns,
                bounds = bounds,
                percentileProvider = percentileProvider,
                rightAttrIndex = 1
            )

            EvolutionContext.frontStack.clear()

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

            auc to scoredFront
        }
    }

    val results = executor.invokeAll(tasks)
        .mapNotNull { it.get() }

    executor.shutdown()

    require(results.isNotEmpty()) { "No valid synthetic fronts generated." }

    val sorted = results.sortedBy { it.first }
    val medianIdx = sorted.size / 2

    val (medianAuc, medianScoredFront) = sorted[medianIdx]

    MedianFront.scoredFront = medianScoredFront
    MedianFront.auc = medianAuc
    MedianFront.initialized = true

    val totalElapsed = (System.nanoTime() - globalStart) / 1_000_000_000.0

    println("Median front ready: median AUC = %.4f".format(medianAuc))
    println("TOTAL median-front time: %.2fs".format(totalElapsed))
}
