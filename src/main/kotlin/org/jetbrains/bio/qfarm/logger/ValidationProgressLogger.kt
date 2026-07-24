package org.jetbrains.bio.qfarm.logger

import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.params.GREEN
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.YELLOW


enum class ValidationProgressStage {
    INITIALIZATION,
    RANDOM_AUC,
    RULE_VALIDATION,
    FINISHING,
    FINISHED
}


/**
 * Timing of one completely reevaluated and validated rule.
 *
 * depth always starts from 1 and equals the number of attributes
 * contained in the rule.
 */
data class ValidationRuleTiming(
    val depth: Int,
    val rule: String,

    /**
     * Time spent only inside fullTopRange(...).
     */
    val evolutionSeconds: Double,

    /**
     * Time spent on:
     *
     * - parent resolution;
     * - DeLong/AUC calculations;
     * - random-AUC testing;
     * - recordStep;
     * - attribute matching;
     * - Spearman comparison;
     * - metadata enrichment;
     * - printing and other local work.
     */
    val validationSeconds: Double,

    val validated: Boolean,
    val failure: String
) {
    val totalSeconds: Double
        get() =
            evolutionSeconds +
                    validationSeconds
}


/**
 * Average timings for rules of one depth.
 *
 * Evolution and validation overhead are intentionally kept separate.
 */
data class ValidationDepthStatistics(
    var processedRules: Int = 0,

    var evolutionObservations: Int = 0,
    var validationObservations: Int = 0,

    var averageEvolutionSeconds: Double = 0.0,
    var averageValidationSeconds: Double = 0.0
) {

    fun update(
        timing: ValidationRuleTiming
    ) {
        processedRules++

        evolutionObservations++

        averageEvolutionSeconds =
            updateAverage(
                previous = averageEvolutionSeconds,
                observation = timing.evolutionSeconds,
                observationCount = evolutionObservations
            )

        validationObservations++

        averageValidationSeconds =
            updateAverage(
                previous = averageValidationSeconds,
                observation = timing.validationSeconds,
                observationCount = validationObservations
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
}


class ValidationProgressLogger(
    private val randomBaselineColumns: Int,

    /**
     * Initial estimate for one fullTopRange(...) call.
     */
    private val initialEvolutionSeconds: Double,

    /**
     * Initial estimate for the validation work surrounding one evolution.
     */
    private val initialValidationSeconds: Double,

    /**
     * Initial estimate for the complete random-AUC stage.
     */
    private val initialRandomAucSeconds: Double,

    /**
     * Initial estimate for exports and final writer work.
     */
    private val initialFinalizationSeconds: Double
) {

    // ============================================================
    // Runtime
    // ============================================================

    private var startNano = 0L
    private var stageStartNano = 0L

    private var stage =
        ValidationProgressStage.INITIALIZATION

    // ============================================================
    // Rule counts
    // ============================================================

    private val totalRulesByDepth =
        mutableMapOf<Int, Long>()

    private val remainingRulesByDepth =
        mutableMapOf<Int, Long>()

    var processedRules = 0L
        private set

    var remainingRules = 0L
        private set

    var totalRules = 0L
        private set

    var validatedRules = 0L
        private set

    var failedRules = 0L
        private set

    // ============================================================
    // Random AUC
    // ============================================================

    var randomCompleted = 0
        private set

    private var measuredRandomAucSeconds: Double? =
        null

    // ============================================================
    // Finalization
    // ============================================================

    private var measuredFinalizationSeconds: Double? =
        null

    // ============================================================
    // Empirical timing
    // ============================================================

    private val depthStatistics =
        mutableMapOf<Int, ValidationDepthStatistics>()

    // ============================================================
    // Lifecycle
    // ============================================================

    /**
     * Must be called before random-AUC generation starts.
     *
     * The complete validation rule set is known in advance, so the
     * initial remaining work can be calculated exactly by depth.
     */
    fun runStarted(
        ruleDepths: List<Int>
    ) {
        require(ruleDepths.all { it >= 1 }) {
            "Validation rule depths must start from 1."
        }

        startNano =
            System.nanoTime()

        stageStartNano =
            startNano

        stage =
            ValidationProgressStage.RANDOM_AUC

        initializeRules(ruleDepths)

        printProgress()
    }

    fun randomBaselineFinished() {
        measuredRandomAucSeconds =
            elapsedSinceStageStart()

        randomCompleted =
            randomBaselineColumns

        stage =
            ValidationProgressStage.RULE_VALIDATION

        stageStartNano =
            System.nanoTime()

        printProgress()
    }

    /**
     * Called exactly once after one rule has been completely reevaluated,
     * compared, enriched and recorded.
     */
    fun ruleFinished(
        timing: ValidationRuleTiming
    ) {
        require(timing.depth >= 1) {
            "Validation rule depth ${timing.depth} must be >= 1."
        }

        processedRules++

        if (timing.validated) {
            validatedRules++
        } else {
            failedRules++
        }

        removeCompletedRule(
            depth = timing.depth
        )

        val statistics =
            depthStatistics.getOrPut(
                timing.depth
            ) {
                ValidationDepthStatistics()
            }

        statistics.update(timing)

        refreshTotals()

        printProgress(timing)
    }

    /**
     * Called after all rules have been validated and before exports begin.
     */
    fun validationFinished() {
        stage =
            ValidationProgressStage.FINISHING

        stageStartNano =
            System.nanoTime()

        refreshTotals()
        printProgress()
    }

    fun runFinished() {
        measuredFinalizationSeconds =
            elapsedSinceStageStart()

        stage =
            ValidationProgressStage.FINISHED

        remainingRulesByDepth.clear()

        refreshTotals()
        printProgress()
    }

    // ============================================================
    // Rule initialization
    // ============================================================

    private fun initializeRules(
        ruleDepths: List<Int>
    ) {
        totalRulesByDepth.clear()
        remainingRulesByDepth.clear()

        for (depth in ruleDepths) {
            totalRulesByDepth[depth] =
                (totalRulesByDepth[depth] ?: 0L) + 1L

            remainingRulesByDepth[depth] =
                (remainingRulesByDepth[depth] ?: 0L) + 1L
        }

        refreshTotals()
    }

    private fun removeCompletedRule(
        depth: Int
    ) {
        val current =
            remainingRulesByDepth[depth] ?: 0L

        remainingRulesByDepth[depth] =
            (current - 1L).coerceAtLeast(0L)
    }

    private fun refreshTotals() {
        remainingRules =
            remainingRulesByDepth.values.sum()

        totalRules =
            totalRulesByDepth.values.sum()
    }

    // ============================================================
    // ETA
    // ============================================================

    private fun estimatedRemainingSeconds(): Double {
        return when (stage) {
            ValidationProgressStage.INITIALIZATION ->
                initialRandomAucSeconds +
                        estimatedRulesSeconds() +
                        initialFinalizationSeconds

            ValidationProgressStage.RANDOM_AUC ->
                remainingRandomAucSeconds() +
                        estimatedRulesSeconds() +
                        initialFinalizationSeconds

            ValidationProgressStage.RULE_VALIDATION ->
                estimatedRulesSeconds() +
                        initialFinalizationSeconds

            ValidationProgressStage.FINISHING ->
                remainingFinalizationSeconds()

            ValidationProgressStage.FINISHED ->
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

    private fun estimatedRulesSeconds(): Double {
        var total = 0.0

        for (
        (depth, count)
        in remainingRulesByDepth
        ) {
            total +=
                count *
                        estimatedRuleSeconds(depth)
        }

        return total
    }

    private fun estimatedRuleSeconds(
        depth: Int
    ): Double {
        return evolutionSecondsFor(depth) +
                validationSecondsFor(depth)
    }

    /**
     * Reference order:
     *
     * 1. same depth;
     * 2. closest measured shallower depth;
     * 3. initial evolution estimate.
     */
    private fun evolutionSecondsFor(
        depth: Int
    ): Double {
        for (referenceDepth in depth downTo 1) {
            val statistics =
                depthStatistics[referenceDepth]
                    ?: continue

            if (statistics.evolutionObservations > 0) {
                return statistics.averageEvolutionSeconds
            }
        }

        return initialEvolutionSeconds
    }

    /**
     * Validation overhead remains separate from evolution time.
     */
    private fun validationSecondsFor(
        depth: Int
    ): Double {
        for (referenceDepth in depth downTo 1) {
            val statistics =
                depthStatistics[referenceDepth]
                    ?: continue

            if (statistics.validationObservations > 0) {
                return statistics.averageValidationSeconds
            }
        }

        return initialValidationSeconds
    }

    // ============================================================
    // Progress
    // ============================================================

    private fun progressPercent(): Double {
        if (stage == ValidationProgressStage.FINISHED) {
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
        timing: ValidationRuleTiming? = null
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
            "${GREEN}QFARM VALIDATION PROGRESS${RESET}"
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
                    elapsed + remaining
                )
            }"
        )

        println()

        when (stage) {
            ValidationProgressStage.RANDOM_AUC -> {
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

                println(
                    "Rules total : $totalRules"
                )
            }

            ValidationProgressStage.RULE_VALIDATION,
            ValidationProgressStage.FINISHING,
            ValidationProgressStage.FINISHED -> {
                println(
                    "Rules       : " +
                            "$processedRules done, " +
                            "$remainingRules left, " +
                            "$totalRules total"
                )

                println(
                    "Validated   : $validatedRules"
                )

                println(
                    "Failed      : $failedRules"
                )

                timing?.let {
                    printLastRule(it)
                }
            }

            ValidationProgressStage.INITIALIZATION ->
                Unit
        }

        println(
            GREEN +
                    "════════════════════════════════════════════════════════════" +
                    RESET
        )
    }

    private fun printLastRule(
        timing: ValidationRuleTiming
    ) {
        println()
        println(
            "${CYAN}Last validated rule${RESET}"
        )

        println(
            "Rule        : ${timing.rule}"
        )

        println(
            "Depth       : ${timing.depth}"
        )

        println(
            "Result      : ${
                if (timing.validated) {
                    "PASS"
                } else {
                    "FAIL"
                }
            }"
        )

        println(
            "Failure     : ${timing.failure}"
        )

        println(
            "Evolution   : ${
                formatPreciseSeconds(
                    timing.evolutionSeconds
                )
            }"
        )

        println(
            "Validation  : ${
                formatPreciseSeconds(
                    timing.validationSeconds
                )
            }"
        )

        println(
            "Rule total  : ${
                formatPreciseSeconds(
                    timing.totalSeconds
                )
            }"
        )

        println(
            "Avg evo d${timing.depth}: ${
                formatPreciseSeconds(
                    evolutionSecondsFor(
                        timing.depth
                    )
                )
            }"
        )

        println(
            "Avg val d${timing.depth}: ${
                formatPreciseSeconds(
                    validationSecondsFor(
                        timing.depth
                    )
                )
            }"
        )
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
            (rounded % 3600) / 60

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

        return "%.3f s".format(seconds)
    }
}
