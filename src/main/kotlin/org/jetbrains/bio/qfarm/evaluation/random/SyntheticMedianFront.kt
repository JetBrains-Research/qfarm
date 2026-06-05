package org.jetbrains.bio.qfarm.evaluation.random

import org.jetbrains.bio.qfarm.bounds
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.rightAttrIndex
import org.jetbrains.bio.qfarm.sortedColumns
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

object MedianFront {
    lateinit var scoredFront: ScoredFront
    lateinit var datasetWithHeader: DatasetWithHeader
    var auc: Double = Double.NaN
}

object RandomAucBaseline {
    lateinit var aucs: List<Double>
    var initialized: Boolean = false

    fun requireReady() {
        require(::aucs.isInitialized && aucs.isNotEmpty()) {
            "Level1RandomAucBaseline was not initialized. " +
                    "Call generateLevel1RandomAucBaseline(...) before tree traversal or reconstruction."
        }
    }
}

data class SyntheticResult(
    val auc: Double,
    val front: ScoredFront,
    val dataset: DatasetWithHeader
)

//fun generateRandomAucBaseline(
//    nColumns: Int = 50   // 50 is enough in practice
//): List<Double> {
//    if (RandomAucBaseline.initialized) return emptyList()
//
//    val globalStart = System.nanoTime()
//
//    println("Generating random AUC baseline (parallel)...")
//
//    val baseData = datasetWithHeader.data
//    val labels = datasetWithHeader.labels
//    val nRows = baseData.size
//
//    val rhsIndex = rightAttrIndex
//    val rhsColumn = baseData.map { it[rhsIndex] }
//
//    val rhsSorted = sortedColumns[rhsIndex]
//    val rhsBounds = bounds[rhsIndex]
//
//    val nThreads = Runtime.getRuntime().availableProcessors()
//    val executor = Executors.newFixedThreadPool(nThreads)
//
//    val tasks = (0 until nColumns).map { colIdx ->
//        Callable<SyntheticResult?> {
//
//            val start = System.nanoTime()
//            val rng = ThreadLocalRandom.current()
//
//            println("[Baseline] Column ${colIdx + 1}/$nColumns START")
//
//            // --- 1. synthetic column ---
//            val column = DoubleArray(nRows) { rng.nextDouble() }
//
//            // --- 2. sorted + bounds (cheap) ---
//            val synSorted = column.copyOf().apply { sort() }
//
//            val localSortedColumns = listOf(synSorted, rhsSorted)
//            val localBounds = arrayOf(
//                doubleArrayOf(synSorted.first(), synSorted.last()),
//                rhsBounds
//            )
//
//            val percentileProvider = SortedColumnsPercentileProvider(localSortedColumns)
//
//            // --- 3. dataset ---
//            val data = ArrayList<DoubleArray>(nRows)
//            for (i in 0 until nRows) {
//                data.add(doubleArrayOf(column[i], rhsColumn[i]))
//            }
//
//            val header = listOf("Median", columnNames[rhsIndex])
//
//            val localDataset = DatasetWithHeader(
//                header = header,
//                data = data,
//                labels = labels
//            )
//
//            val syntheticEnv = EvolutionEnvironment(
//                datasetWithHeader = localDataset,
//                columnNames = header,
//                sortedColumns = localSortedColumns,
//                bounds = localBounds,
//                percentileProvider = percentileProvider,
//                rightAttrIndex = 1
//            )
//
//            // ⚠️ still global — ideally remove later
//            EvolutionContext.frontStack.clear()
//
//            // --- 4. evolution ---
//            val scoredFront = fullTopRange(listOf(0), env = syntheticEnv)
//
//            if (scoredFront.scores.isEmpty()) {
//                println("[Baseline] Column ${colIdx + 1} skipped (empty front)")
//                return@Callable null
//            }
//
//            val auc = AUC.compute(labels, scoredFront.scores)
//
//            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
//
//            println(
//                "[Baseline] Column ${colIdx + 1} DONE | AUC=%.4f | time=%.2fs"
//                    .format(auc, elapsed)
//            )
//
//            SyntheticResult(auc, scoredFront, localDataset)
//        }
//    }
//
//    val results = executor.invokeAll(tasks)
//        .mapNotNull { it.get() }
//
//    executor.shutdown()
//
//    require(results.isNotEmpty()) { "No valid synthetic fronts generated." }
//
//    // Store full random AUC pool
//    val aucs = results.map { it.auc }
//
//    // --- 5. median selection ---
//    val sorted = results.sortedBy { it.auc }
//    val median = sorted[sorted.size / 2]
//
////    RandomAucBaseline.aucs = aucs
//    RandomAucBaseline.initialized = true
//
//    MedianFront.scoredFront = median.front
//    MedianFront.datasetWithHeader = median.dataset
//    MedianFront.auc = median.auc
//
//    val totalElapsed = (System.nanoTime() - globalStart) / 1_000_000_000.0
//
//    val sortedAucs = aucs.sorted()
//    val medianAuc = sortedAucs[sortedAucs.size / 2]
//
//    println(
//        "Level-1 random AUC baseline ready | " +
//                "n=${aucs.size} | " +
//                "median=${"%.4f".format(medianAuc)} | " +
//                "max=${"%.4f".format(aucs.maxOrNull())}"
//    )
//
//    println("TOTAL level-1 random-baseline time: %.2fs".format(totalElapsed))
//
//    return aucs
//}

fun empiricalAucPValueGreater(
    observedAuc: Double,
    randomAucs: List<Double>
): Double {
    require(randomAucs.isNotEmpty()) {
        "Random AUC baseline must not be empty."
    }

    val greaterOrEqual = randomAucs.count { it >= observedAuc }

    // TODO: yes, but if perfect, then 1/51 = 0.0196, so wtf...
    return (greaterOrEqual + 1.0) / (randomAucs.size + 1.0)
}

fun bonferroniCorrect(
    rawP: Double,
    nTests: Int
): Double {
    require(nTests > 0) { "nTests must be > 0" }
    return (rawP * nTests).coerceAtMost(1.0)
}
