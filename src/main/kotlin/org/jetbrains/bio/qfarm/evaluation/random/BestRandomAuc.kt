package org.jetbrains.bio.qfarm.evaluation.random

import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.SplittableRandom
import java.util.random.RandomGenerator
import kotlin.math.min

data class WindowRule(
    val start: Int,
    val endExclusive: Int,
    val support: Int,
    val positives: Int
) {
    val positiveRate: Double
        get() = positives.toDouble() / support
}

data class AnalyticalShuffleResult(
    val auc: Double,
    val producedWindows: Int,
    val survivedWindows: Int,
    val winningWindows: List<WindowRule>,
    val maxScore: Double,
    val coveredRows: Int
)

fun generateAnalyticalRandomAucBaseline(
    nShuffles: Int = hp.randomAucBaselineColumns,
) {
    val start = System.nanoTime()

    println("Generating analytical random AUC baseline...")

    val labels = datasetWithHeader.labels
    val nRows = labels.size
    val positives = labels.count { it == 1 }

    val minSupport = hp.minSupport.coerceAtLeast(1)
    val maxSupport = min(
        hp.maxSupport,
        (hp.maxWidth * nRows + minSupport).toInt()
    ).coerceAtLeast(minSupport)

    val windowSizes = buildWindowSizes(
        minSupport = minSupport,
        maxSupport = maxSupport,
        nRows = nRows
    )

    println(
        "[Analytical baseline] nShuffles=$nShuffles | " +
                "nRows=$nRows | positives=$positives | " +
                "windowSizes=${windowSizes.size} | " +
                "support=$minSupport..$maxSupport | maxWidth=${hp.maxWidth}"
    )

    println(
        "[Analytical baseline] sampled k preview: " +
                windowSizes.take(8).joinToString(", ") +
                if (windowSizes.size > 8) " ... ${windowSizes.takeLast(3).joinToString(", ")}" else ""
    )

    val nThreads = Runtime.getRuntime().availableProcessors()
    val executor = Executors.newFixedThreadPool(nThreads)

    val tasks = (0 until nShuffles).map { idx ->
        Callable {

            val rng = SplittableRandom(
                seedForShuffle(
                    rootSeed = hp.seed,
                    shuffleIndex = idx
                )
            )

            val result = analyticalRandomAucOneShuffleDetailed(
                labels = labels,
                windowSizes = windowSizes,
                random = rng
            )

            if (
                idx == 0 ||
                (idx + 1) % 25 == 0 ||
                idx + 1 == nShuffles
            ) {
                println(
                    "[Analytical baseline] ${idx + 1}/$nShuffles | " +
                            "AUC=${"%.4f".format(result.auc)} | " +
                            "windows=${result.producedWindows}->${result.survivedWindows} | " +
                            "coveredRows=${result.coveredRows} | " +
                            "maxScore=${"%.0f".format(result.maxScore)}"
                )
            }

            result
        }
    }

    val results = executor.invokeAll(tasks).map { it.get() }

    executor.shutdown()

    val aucs = results.map { it.auc }
    RandomAucBaseline.aucs = aucs

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    val sorted = aucs.sorted()

    val avgSurvivors = results.map { it.survivedWindows }.average()
    val avgProduced = results.map { it.producedWindows }.average()
    val avgCoveredRows = results.map { it.coveredRows }.average()
    val avgMaxScore = results.map { it.maxScore }.average()

    println(
        "Analytical random AUC baseline ready | " +
                "n=${aucs.size} | " +
                "median=${"%.4f".format(sorted[sorted.size / 2])} | " +
                "p95=${"%.4f".format(percentile(sorted, 95.0))} | " +
                "max=${"%.4f".format(aucs.maxOrNull())} | " +
                "time=${"%.2f".format(elapsed)}s"
    )

    println(
        "[Analytical baseline summary] " +
                "avgWindows=${"%.1f".format(avgProduced)}->${"%.1f".format(avgSurvivors)} | " +
                "avgCoveredRows=${"%.1f".format(avgCoveredRows)} | " +
                "avgMaxScore=${"%.2f".format(avgMaxScore)}"
    )
}

fun analyticalRandomAucOneShuffleDetailed(
    labels: IntArray,
    windowSizes: List<Int>,
    random: RandomGenerator
): AnalyticalShuffleResult {

    val n = labels.size
    val order = shuffledIndices(
        n = n,
        random = random
    )

    val shuffledLabels = IntArray(n)
    for (i in 0 until n) {
        shuffledLabels[i] = labels[order[i]]
    }

    val windowBuild = buildBestWindowsForShuffleDetailed(
        shuffledLabels = shuffledLabels,
        windowSizes = windowSizes
    )

    val scores = coverageScoresFromWindows(
        nRows = n,
        order = order,
        windows = windowBuild.survivedWindows
    )

    val auc = AUC.compute(labels, scores)

    return AnalyticalShuffleResult(
        auc = auc,
        producedWindows = windowBuild.producedWindows.size,
        survivedWindows = windowBuild.survivedWindows.size,
        winningWindows = windowBuild.survivedWindows,
        maxScore = scores.maxOrNull() ?: 0.0,
        coveredRows = scores.count { it > 0.0 }
    )
}

data class WindowBuildResult(
    val producedWindows: List<WindowRule>,
    val survivedWindows: List<WindowRule>
)

fun shuffledIndices(
    n: Int,
    random: RandomGenerator
): IntArray {

    val arr = IntArray(n) { it }

    for (i in n - 1 downTo 1) {

        val j = random.nextInt(i + 1)

        val tmp = arr[i]
        arr[i] = arr[j]
        arr[j] = tmp
    }

    return arr
}

fun buildBestWindowsForShuffleDetailed(
    shuffledLabels: IntArray,
    windowSizes: List<Int>
): WindowBuildResult {
    val n = shuffledLabels.size
    val prefix = IntArray(n + 1)

    for (i in 0 until n) {
        prefix[i + 1] = prefix[i] + shuffledLabels[i]
    }

    val windows = mutableListOf<WindowRule>()

    for (k in windowSizes) {
        if (k !in 1..n) continue

        var bestStart = 0
        var bestPositiveSum = -1

        for (start in 0..(n - k)) {
            val positives = prefix[start + k] - prefix[start]

            if (positives > bestPositiveSum) {
                bestPositiveSum = positives
                bestStart = start
            }
        }

        windows += WindowRule(
            start = bestStart,
            endExclusive = bestStart + k,
            support = k,
            positives = bestPositiveSum
        )
    }

    val survived = paretoFilterWindows(windows)

    return WindowBuildResult(
        producedWindows = windows,
        survivedWindows = survived
    )
}

fun paretoFilterWindows(
    windows: List<WindowRule>
): List<WindowRule> {
    return windows.filter { w ->
        windows.none { other ->
            other !== w &&
                    other.support >= w.support &&
                    other.positiveRate >= w.positiveRate &&
                    (
                            other.support > w.support ||
                                    other.positiveRate > w.positiveRate
                            )
        }
    }.sortedBy { it.support }
}

fun coverageScoresFromWindows(
    nRows: Int,
    order: IntArray,
    windows: List<WindowRule>
): DoubleArray {
    val counts = IntArray(nRows)

    for (w in windows) {
        for (pos in w.start until w.endExclusive) {
            val originalRowIndex = order[pos]
            counts[originalRowIndex]++
        }
    }

    return DoubleArray(nRows) { i ->
        counts[i].toDouble()
    }
}

fun buildWindowSizes(
    minSupport: Int,
    maxSupport: Int,
    nRows: Int
): List<Int> {
    val lo = minSupport.coerceIn(1, nRows)
    val hi = maxSupport.coerceIn(lo, nRows)

    val steps = hp.popSizeFull

    if (hi - lo <= steps) {
        return (lo..hi).toList()
    }

    val sizes = mutableSetOf<Int>()

    sizes += lo
    sizes += hi

    for (i in 0..steps) {
        val k = lo + ((hi - lo).toDouble() * i / steps).toInt()
        sizes += k.coerceIn(lo, hi)
    }

    return sizes.sorted()
}

fun percentile(
    sortedValues: List<Double>,
    p: Double
): Double {
    require(sortedValues.isNotEmpty()) {
        "Cannot compute percentile of empty list."
    }

    val clamped = p.coerceIn(0.0, 100.0)
    val pos = (sortedValues.size - 1) * (clamped / 100.0)
    val lo = kotlin.math.floor(pos).toInt()
    val hi = kotlin.math.ceil(pos).toInt()

    if (lo == hi) return sortedValues[lo]

    val weight = pos - lo
    return sortedValues[lo] * (1.0 - weight) + sortedValues[hi] * weight
}

private fun seedForShuffle(
    rootSeed: Long,
    shuffleIndex: Int
): Long {

    return rootSeed +
            shuffleIndex.toLong() *
            -7046029254386353131L
}
