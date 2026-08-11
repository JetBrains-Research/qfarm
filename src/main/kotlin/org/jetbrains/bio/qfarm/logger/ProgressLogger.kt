package org.jetbrains.bio.qfarm.logger

import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.params.GREEN
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.YELLOW


enum class ProgressStage {
    INITIALIZATION,
    RANDOM_AUC,
    TREE_SEARCH,
    FINISHING,
    FINISHED
}


/**
 * Timing of one completed prefix expansion.
 *
 * The prefix itself is not evolved. The expensive work consists of:
 *
 * 1. cheap evolutions for all possible prefix + attribute additions;
 * 2. full evolutions for the selected additions;
 * 3. filtering and other overhead.
 *
 * prefixDepth equals the number of attributes already present in the prefix.
 *
 * Examples:
 * - ROOT has prefixDepth = 0
 * - a one-attribute prefix has prefixDepth = 1
 */
data class NodeTiming(
    val prefixDepth: Int,
    val rule: String,

    val cheapCandidates: Int,
    val fullCandidates: Int,

    val survivingChildren: Int,

    /**
     * Wall-clock duration of the complete parallel cheap batch.
     */
    val cheapSeconds: Double,

    /**
     * Wall-clock duration of the complete parallel full batch.
     */
    val fullSeconds: Double,

    /**
     * Beam selection, filtering, statistics, logging and other work.
     */
    val overheadSeconds: Double
) {
    val totalSeconds: Double
        get() =
            cheapSeconds +
                    fullSeconds +
                    overheadSeconds
}


/**
 * Empirical timing reference for expansions of prefixes at one depth.
 *
 * Cheap and full timing statistics are intentionally independent.
 *
 * Since individual coroutine durations are not measured, the inferred
 * duration of one evolution is:
 *
 *     parallel batch wall time / number of sequential parallel waves
 */
data class DepthStatistics(
    var processedExpansions: Int = 0,

    var cheapObservations: Int = 0,
    var fullObservations: Int = 0,
    var overheadObservations: Int = 0,

    var averageCheapSecondsPerEvolution: Double = 0.0,
    var averageFullSecondsPerEvolution: Double = 0.0,
    var averageOverheadSeconds: Double = 0.0
) {

    fun update(
        timing: NodeTiming,
        availableThreads: Int
    ) {
        processedExpansions++

        val cheapWaves = parallelWaves(
            candidates = timing.cheapCandidates,
            threads = availableThreads
        )

        val fullWaves = parallelWaves(
            candidates = timing.fullCandidates,
            threads = availableThreads
        )

        if (cheapWaves > 0) {
            cheapObservations++

            val inferredSecondsPerEvolution =
                timing.cheapSeconds / cheapWaves

            averageCheapSecondsPerEvolution =
                updateAverage(
                    previous =
                        averageCheapSecondsPerEvolution,
                    observation =
                        inferredSecondsPerEvolution,
                    observationCount =
                        cheapObservations
                )
        }

        if (fullWaves > 0) {
            fullObservations++

            val inferredSecondsPerEvolution =
                timing.fullSeconds / fullWaves

            averageFullSecondsPerEvolution =
                updateAverage(
                    previous =
                        averageFullSecondsPerEvolution,
                    observation =
                        inferredSecondsPerEvolution,
                    observationCount =
                        fullObservations
                )
        }

        overheadObservations++

        averageOverheadSeconds =
            updateAverage(
                previous =
                    averageOverheadSeconds,
                observation =
                    timing.overheadSeconds,
                observationCount =
                    overheadObservations
            )
    }

    private fun updateAverage(
        previous: Double,
        observation: Double,
        observationCount: Int
    ): Double {
        val weight =
            1.0 / observationCount

        return (1.0 - weight) * previous +
                weight * observation
    }

    companion object {

        private fun parallelWaves(
            candidates: Int,
            threads: Int
        ): Int {
            if (candidates <= 0) {
                return 0
            }

            return (
                    candidates +
                            threads -
                            1
                    ) / threads
        }
    }
}


class ProgressLogger(
    private val maxDepth: Int,
    private val maxFirstChildren: Int,
    private val maxChildren: Int,

    private val randomBaselineColumns: Int,

    availableThreads: Int,
    private val searchableAttributes: Int,

    /**
     * Initial estimate of one cheap evolution.
     *
     * Future cheap batch time is:
     *
     * ceil(cheap evolutions / threads)
     *     * cheap seconds per evolution
     */
    private val initialCheapEvolutionSeconds: Double,

    /**
     * Initial estimate of one full evolution.
     *
     * Future full batch time is:
     *
     * ceil(full evolutions / threads)
     *     * full seconds per evolution
     */
    private val initialFullEvolutionSeconds: Double,

    /**
     * Initial estimate for the complete random AUC stage.
     */
    private val initialRandomAucSeconds: Double,

    /**
     * Initial estimate for exports and other post-search work.
     */
    private val initialFinalizationSeconds: Double
) {

    private val availableThreads =
        availableThreads.coerceAtLeast(1)

    // ============================================================
    // Runtime
    // ============================================================

    private var startNano = 0L
    private var stageStartNano = 0L

    private var stage =
        ProgressStage.INITIALIZATION

    // ============================================================
    // Completed work
    // ============================================================

    var processedExpansions = 0L
        private set

    var completedCheapEvolutions = 0L
        private set

    var completedFullEvolutions = 0L
        private set

    // ============================================================
    // Remaining tree structure
    // ============================================================

    /**
     * Number of prefix expansions still expected at every prefix depth.
     *
     * Depth 0:
     *   ROOT expansion.
     *
     * Depth 1:
     *   expansions of surviving one-attribute prefixes.
     *
     * Prefixes at maxDepth are leaves and are never expanded.
     */
    private val remainingExpansionsByDepth =
        mutableMapOf<Int, Long>()

    var estimatedRemainingExpansions = 0L
        private set

    var estimatedTotalExpansions = 0L
        private set

    var estimatedRemainingCheapEvolutions = 0L
        private set

    var estimatedRemainingFullEvolutions = 0L
        private set

    var estimatedTotalCheapEvolutions = 0L
        private set

    var estimatedTotalFullEvolutions = 0L
        private set

    // ============================================================
    // Random AUC / finalization
    // ============================================================

    var randomCompleted = 0
        private set

    private var measuredRandomAucSeconds: Double? =
        null

    private var measuredFinalizationSeconds: Double? =
        null

    // ============================================================
    // Empirical tree timing
    // ============================================================

    private val depthStatistics =
        mutableMapOf<Int, DepthStatistics>()

    // ============================================================
    // Lifecycle
    // ============================================================

    fun runStarted() {
        startNano =
            System.nanoTime()

        stageStartNano =
            startNano

        stage =
            ProgressStage.RANDOM_AUC

        initializeMaximumTree()
        refreshTotals()

        printProgress()
    }

    fun randomBaselineFinished() {
        measuredRandomAucSeconds =
            elapsedSinceStageStart()

        randomCompleted =
            randomBaselineColumns

        stage =
            ProgressStage.TREE_SEARCH

        stageStartNano =
            System.nanoTime()

        printProgress()
    }

    /**
     * Called after one prefix expansion is fully complete and filtered.
     */
    fun nodeFinished(
        timing: NodeTiming
    ) {
        require(
            timing.prefixDepth in 0 until maxDepth
        ) {
            "Prefix depth ${timing.prefixDepth} " +
                    "must be in 0 until $maxDepth"
        }

        processedExpansions++

        completedCheapEvolutions +=
            timing.cheapCandidates.toLong()

        completedFullEvolutions +=
            timing.fullCandidates.toLong()

        removeCompletedExpansion(
            prefixDepth =
                timing.prefixDepth
        )

        pruneMissingFutureBranches(timing)

        val statistics =
            depthStatistics.getOrPut(
                timing.prefixDepth
            ) {
                DepthStatistics()
            }

        statistics.update(
            timing =
                timing,
            availableThreads =
                availableThreads
        )

        refreshTotals()
        printProgress(timing)
    }

    /**
     * Called after treeTraversal finishes and before exports begin.
     */
    fun treeSearchFinished() {
        stage =
            ProgressStage.FINISHING

        stageStartNano =
            System.nanoTime()

        refreshTotals()
        printProgress()
    }

    fun runFinished() {
        measuredFinalizationSeconds =
            elapsedSinceStageStart()

        stage =
            ProgressStage.FINISHED

        remainingExpansionsByDepth.clear()

        refreshTotals()
        printProgress()
    }

    // ============================================================
    // Initial tree estimate
    // ============================================================

    private fun initializeMaximumTree() {
        remainingExpansionsByDepth.clear()

        if (
            maxDepth <= 0 ||
            searchableAttributes <= 0
        ) {
            return
        }

        /*
         * ROOT is a prefix of depth 0 and is expanded once.
         */
        remainingExpansionsByDepth[0] =
            1L

        /*
         * Prefixes at maxDepth are leaves, so the final expandable
         * prefix depth is maxDepth - 1.
         */
        for (
        prefixDepth in
        0 until maxDepth - 1
        ) {
            val currentExpansionCount =
                remainingExpansionsByDepth[prefixDepth]
                    ?: 0L

            if (currentExpansionCount == 0L) {
                break
            }

            val maximumSurvivingChildren =
                expectedChildren(prefixDepth)

            if (maximumSurvivingChildren == 0) {
                break
            }

            remainingExpansionsByDepth[prefixDepth + 1] =
                safeMultiply(
                    currentExpansionCount,
                    maximumSurvivingChildren.toLong()
                )
        }
    }

    /**
     * Maximum selected children assumed for one prefix.
     */
    fun expectedChildren(
        prefixDepth: Int
    ): Int {
        val remainingAttributes =
            cheapEvolutionCount(prefixDepth)

        val configuredLimit =
            if (prefixDepth == 0) {
                maxFirstChildren
            } else {
                maxChildren
            }

        return minOf(
            configuredLimit,
            remainingAttributes
        )
    }

    /**
     * All attributes not already present in the prefix receive
     * cheap evolution.
     */
    private fun cheapEvolutionCount(
        prefixDepth: Int
    ): Int =
        (
                searchableAttributes -
                        prefixDepth
                ).coerceAtLeast(0)

    /**
     * Only the selected candidates receive full evolution.
     */
    private fun fullEvolutionCount(
        prefixDepth: Int
    ): Int =
        expectedChildren(prefixDepth)

    // ============================================================
    // Dynamic pruning
    // ============================================================

    private fun removeCompletedExpansion(
        prefixDepth: Int
    ) {
        val current =
            remainingExpansionsByDepth[prefixDepth]
                ?: 0L

        remainingExpansionsByDepth[prefixDepth] =
            (
                    current -
                            1L
                    ).coerceAtLeast(0L)
    }

    /**
     * Removes future work corresponding to rejected or missing children.
     */
    private fun pruneMissingFutureBranches(
        timing: NodeTiming
    ) {
        val childPrefixDepth =
            timing.prefixDepth + 1

        /*
         * Children at maxDepth are leaves and will not be expanded.
         */
        if (childPrefixDepth >= maxDepth) {
            return
        }

        val maximumChildren =
            expectedChildren(
                timing.prefixDepth
            )

        val missingChildren =
            (
                    maximumChildren -
                            timing.survivingChildren
                    ).coerceAtLeast(0)

        if (missingChildren == 0) {
            return
        }

        /*
         * Each missing child would have been one expansion at the
         * next prefix depth.
         */
        var removedExpansions =
            missingChildren.toLong()

        for (
        futurePrefixDepth in
        childPrefixDepth until maxDepth
        ) {
            subtractRemainingExpansions(
                prefixDepth =
                    futurePrefixDepth,
                amount =
                    removedExpansions
            )

            if (
                futurePrefixDepth >=
                maxDepth - 1
            ) {
                break
            }

            val childrenPerExpansion =
                expectedChildren(
                    futurePrefixDepth
                )

            if (childrenPerExpansion == 0) {
                break
            }

            removedExpansions =
                safeMultiply(
                    removedExpansions,
                    childrenPerExpansion.toLong()
                )
        }
    }

    private fun subtractRemainingExpansions(
        prefixDepth: Int,
        amount: Long
    ) {
        val current =
            remainingExpansionsByDepth[prefixDepth]
                ?: 0L

        remainingExpansionsByDepth[prefixDepth] =
            (
                    current -
                            amount
                    ).coerceAtLeast(0L)
    }

    // ============================================================
    // Totals
    // ============================================================

    private fun refreshTotals() {
        estimatedRemainingExpansions =
            remainingExpansionsByDepth.values.sum()

        estimatedTotalExpansions =
            processedExpansions +
                    estimatedRemainingExpansions

        estimatedRemainingCheapEvolutions =
            calculateRemainingCheapEvolutions()

        estimatedRemainingFullEvolutions =
            calculateRemainingFullEvolutions()

        estimatedTotalCheapEvolutions =
            completedCheapEvolutions +
                    estimatedRemainingCheapEvolutions

        estimatedTotalFullEvolutions =
            completedFullEvolutions +
                    estimatedRemainingFullEvolutions
    }

    private fun calculateRemainingCheapEvolutions(): Long {
        var total = 0L

        for (
        (prefixDepth, expansionCount)
        in remainingExpansionsByDepth
        ) {
            total =
                safeAdd(
                    total,
                    safeMultiply(
                        expansionCount,
                        cheapEvolutionCount(
                            prefixDepth
                        ).toLong()
                    )
                )
        }

        return total
    }

    private fun calculateRemainingFullEvolutions(): Long {
        var total = 0L

        for (
        (prefixDepth, expansionCount)
        in remainingExpansionsByDepth
        ) {
            total =
                safeAdd(
                    total,
                    safeMultiply(
                        expansionCount,
                        fullEvolutionCount(
                            prefixDepth
                        ).toLong()
                    )
                )
        }

        return total
    }

    // ============================================================
    // ETA
    // ============================================================

    private fun estimatedRemainingSeconds(): Double {
        return when (stage) {
            ProgressStage.INITIALIZATION ->
                initialRandomAucSeconds +
                        estimatedTreeSeconds() +
                        initialFinalizationSeconds

            ProgressStage.RANDOM_AUC ->
                remainingRandomAucSeconds() +
                        estimatedTreeSeconds() +
                        initialFinalizationSeconds

            ProgressStage.TREE_SEARCH ->
                estimatedTreeSeconds() +
                        initialFinalizationSeconds

            ProgressStage.FINISHING ->
                remainingFinalizationSeconds()

            ProgressStage.FINISHED ->
                0.0
        }
    }

    private fun remainingRandomAucSeconds(): Double {
        return (
                initialRandomAucSeconds -
                        elapsedSinceStageStart()
                ).coerceAtLeast(0.0)
    }

    private fun remainingFinalizationSeconds(): Double {
        return (
                initialFinalizationSeconds -
                        elapsedSinceStageStart()
                ).coerceAtLeast(0.0)
    }

    private fun estimatedTreeSeconds(): Double {
        var total =
            0.0

        for (
        (prefixDepth, expansionCount)
        in remainingExpansionsByDepth
        ) {
            total +=
                expansionCount *
                        estimatedExpansionSeconds(
                            prefixDepth
                        )
        }

        return total
    }

    /**
     * Cheap and full work are estimated independently.
     *
     * Parallelism is applied independently to both batches.
     */
    private fun estimatedExpansionSeconds(
        prefixDepth: Int
    ): Double {
        val cheapRuns =
            cheapEvolutionCount(prefixDepth)

        val fullRuns =
            fullEvolutionCount(prefixDepth)

        val cheapWaves =
            parallelWaves(cheapRuns)

        val fullWaves =
            parallelWaves(fullRuns)

        val cheapSecondsPerEvolution =
            cheapSecondsPerEvolutionFor(
                prefixDepth
            )

        val fullSecondsPerEvolution =
            fullSecondsPerEvolutionFor(
                prefixDepth
            )

        val overheadSeconds =
            overheadSecondsFor(
                prefixDepth
            )

        return cheapWaves *
                cheapSecondsPerEvolution +
                fullWaves *
                fullSecondsPerEvolution +
                overheadSeconds
    }

    /**
     * Reference priority for cheap evolution:
     *
     * 1. same prefix depth;
     * 2. closest measured shallower prefix depth;
     * 3. initial cheap estimate.
     */
    private fun cheapSecondsPerEvolutionFor(
        prefixDepth: Int
    ): Double {
        for (
        depth in
        prefixDepth downTo 0
        ) {
            val statistics =
                depthStatistics[depth]
                    ?: continue

            if (
                statistics.cheapObservations >
                0
            ) {
                return statistics
                    .averageCheapSecondsPerEvolution
            }
        }

        return initialCheapEvolutionSeconds
    }

    /**
     * Reference priority for full evolution:
     *
     * 1. same prefix depth;
     * 2. closest measured shallower prefix depth;
     * 3. initial full estimate.
     */
    private fun fullSecondsPerEvolutionFor(
        prefixDepth: Int
    ): Double {
        for (
        depth in
        prefixDepth downTo 0
        ) {
            val statistics =
                depthStatistics[depth]
                    ?: continue

            if (
                statistics.fullObservations >
                0
            ) {
                return statistics
                    .averageFullSecondsPerEvolution
            }
        }

        return initialFullEvolutionSeconds
    }

    /**
     * Reference priority for expansion overhead:
     *
     * 1. same prefix depth;
     * 2. closest measured shallower prefix depth;
     * 3. zero.
     */
    private fun overheadSecondsFor(
        prefixDepth: Int
    ): Double {
        for (
        depth in
        prefixDepth downTo 0
        ) {
            val statistics =
                depthStatistics[depth]
                    ?: continue

            if (
                statistics.overheadObservations >
                0
            ) {
                return statistics
                    .averageOverheadSeconds
                    .coerceAtLeast(0.0)
            }
        }

        return 0.0
    }

    private fun parallelWaves(
        candidates: Int
    ): Int {
        if (candidates <= 0) {
            return 0
        }

        return (
                candidates +
                        availableThreads -
                        1
                ) / availableThreads
    }

    // ============================================================
    // Progress
    // ============================================================

    private fun progressPercent(): Double {
        if (stage == ProgressStage.FINISHED) {
            return 100.0
        }

        val elapsed =
            elapsedSeconds()

        val remaining =
            estimatedRemainingSeconds()

        val estimatedTotal =
            elapsed + remaining

        if (estimatedTotal <= 0.0) {
            return 0.0
        }

        return (
                100.0 *
                        elapsed /
                        estimatedTotal
                ).coerceIn(
                0.0,
                99.9
            )
    }

    // ============================================================
    // Output
    // ============================================================

    private fun printProgress(
        timing: NodeTiming? = null
    ) {
        val elapsed =
            elapsedSeconds()

        val remaining =
            estimatedRemainingSeconds()

        println()
        println(
            GREEN +
                    "════════════════════════════════════════════════════════════" +
                    RESET
        )
        println(
            "${GREEN}QFARM PROGRESS${RESET}"
        )
        println()

        println(
            "Stage       : $stage"
        )

        println(
            "Progress    : ${
                "%.1f".format(
                    progressPercent()
                )
            } %"
        )

        println(
            "Elapsed     : ${
                formatSeconds(elapsed)
            }"
        )

        println(
            "Time left   : $YELLOW${
                formatSeconds(remaining)
            }$RESET"
        )

        println(
            "Est. total  : ${
                formatSeconds(
                    elapsed +
                            remaining
                )
            }"
        )

        println()

        when (stage) {
            ProgressStage.RANDOM_AUC -> {
                println(
                    "Random AUC  : " +
                            "$randomCompleted / " +
                            "$randomBaselineColumns done"
                )

                println(
                    "Still left  : " +
                            "${
                                randomBaselineColumns -
                                        randomCompleted
                            }"
                )

                println(
                    "Random ETA  : ${
                        formatSeconds(
                            remainingRandomAucSeconds()
                        )
                    }"
                )
            }

            ProgressStage.TREE_SEARCH,
            ProgressStage.FINISHING,
            ProgressStage.FINISHED -> {
                println(
                    "Expansions  : " +
                            "$processedExpansions done, " +
                            "$estimatedRemainingExpansions left, " +
                            "$estimatedTotalExpansions total"
                )

                println(
                    "Cheap evos  : " +
                            "$completedCheapEvolutions done, " +
                            "$estimatedRemainingCheapEvolutions left, " +
                            "$estimatedTotalCheapEvolutions total"
                )

                println(
                    "Full evos   : " +
                            "$completedFullEvolutions done, " +
                            "$estimatedRemainingFullEvolutions left, " +
                            "$estimatedTotalFullEvolutions total"
                )

                println(
                    "Threads     : $availableThreads"
                )

                timing?.let {
                    printLastExpansion(it)
                }
            }

            ProgressStage.INITIALIZATION ->
                Unit
        }

        println(
            GREEN +
                    "════════════════════════════════════════════════════════════" +
                    RESET
        )
    }

    private fun printLastExpansion(
        timing: NodeTiming
    ) {
        val cheapWaves =
            parallelWaves(
                timing.cheapCandidates
            )

        val fullWaves =
            parallelWaves(
                timing.fullCandidates
            )

        val rejected =
            (
                    timing.fullCandidates -
                            timing.survivingChildren
                    ).coerceAtLeast(0)

        println()
        println(
            "${CYAN}Last completed expansion${RESET}"
        )

        println(
            "Prefix      : ${timing.rule}"
        )

        println(
            "Prefix depth: " +
                    "${timing.prefixDepth} / " +
                    "$maxDepth"
        )

        println(
            "Cheap eval  : " +
                    "${timing.cheapCandidates} / " +
                    "${timing.cheapCandidates} done, " +
                    "0 left"
        )

        println(
            "Cheap waves : $cheapWaves"
        )

        if (cheapWaves > 0) {
            println(
                "Avg cheap   : ${
                    formatPreciseSeconds(
                        timing.cheapSeconds /
                                cheapWaves
                    )
                } per evolution"
            )
        }

        println(
            "Full eval   : " +
                    "${timing.fullCandidates} / " +
                    "${timing.fullCandidates} done, " +
                    "0 left"
        )

        println(
            "Full waves  : $fullWaves"
        )

        if (fullWaves > 0) {
            println(
                "Avg full    : ${
                    formatPreciseSeconds(
                        timing.fullSeconds /
                                fullWaves
                    )
                } per evolution"
            )
        }

        println(
            "Children    : " +
                    "${timing.survivingChildren} kept, " +
                    "$rejected rejected"
        )

        println(
            "Cheap time  : ${
                formatSeconds(
                    timing.cheapSeconds
                )
            }"
        )

        println(
            "Full time   : ${
                formatSeconds(
                    timing.fullSeconds
                )
            }"
        )

        println(
            "Total time  : ${
                formatSeconds(
                    timing.totalSeconds
                )
            }"
        )
    }

    private fun timingReferenceText(
        prefixDepth: Int
    ): String {
        val sameDepth =
            depthStatistics[prefixDepth]

        if (
            sameDepth != null &&
            (
                    sameDepth.cheapObservations > 0 ||
                            sameDepth.fullObservations > 0
                    )
        ) {
            return "measured at this depth"
        }

        for (
        depth in
        prefixDepth - 1 downTo 0
        ) {
            val statistics =
                depthStatistics[depth]
                    ?: continue

            if (
                statistics.cheapObservations > 0 ||
                statistics.fullObservations > 0
            ) {
                return "estimated from shallower depth"
            }
        }

        return "initial approximation"
    }

    // ============================================================
    // Time helpers
    // ============================================================

    private fun elapsedSeconds(): Double {
        if (startNano == 0L) {
            return 0.0
        }

        return (
                System.nanoTime() -
                        startNano
                ) / 1_000_000_000.0
    }

    private fun elapsedSinceStageStart(): Double {
        if (stageStartNano == 0L) {
            return 0.0
        }

        return (
                System.nanoTime() -
                        stageStartNano
                ) / 1_000_000_000.0
    }

    private fun formatSeconds(
        seconds: Double
    ): String {
        if (
            !seconds.isFinite() ||
            seconds < 0.0
        ) {
            return "--:--:--"
        }

        val rounded =
            seconds.toLong()

        val hours =
            rounded / 3600

        val minutes =
            (
                    rounded %
                            3600
                    ) / 60

        val remainingSeconds =
            rounded % 60

        return "%02d:%02d:%02d".format(
            hours,
            minutes,
            remainingSeconds
        )
    }

    private fun formatPreciseSeconds(
        seconds: Double
    ): String {
        if (
            !seconds.isFinite() ||
            seconds < 0.0
        ) {
            return "--"
        }

        return "%.3f s".format(
            seconds
        )
    }

    // ============================================================
    // Overflow-safe arithmetic
    // ============================================================

    private fun safeMultiply(
        first: Long,
        second: Long
    ): Long {
        if (
            first == 0L ||
            second == 0L
        ) {
            return 0L
        }

        if (
            first >
            Long.MAX_VALUE / second
        ) {
            return Long.MAX_VALUE
        }

        return first * second
    }

    private fun safeAdd(
        first: Long,
        second: Long
    ): Long {
        if (
            Long.MAX_VALUE - first <
            second
        ) {
            return Long.MAX_VALUE
        }

        return first + second
    }
}
