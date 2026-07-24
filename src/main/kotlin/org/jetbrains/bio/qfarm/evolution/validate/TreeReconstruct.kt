package org.jetbrains.bio.qfarm.evolution.validate

import org.jetbrains.bio.qfarm.VALIDATION_PROGRESS
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.compare.extractBarsFromLabel
import org.jetbrains.bio.qfarm.compare.smoothedSpearmanPerNode
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.random.RandomAucBaseline
import org.jetbrains.bio.qfarm.evaluation.random.bonferroniCorrect
import org.jetbrains.bio.qfarm.evaluation.random.empiricalAucPValueGreater
import org.jetbrains.bio.qfarm.evaluation.fronts.frontDistance
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.logger.ValidationRuleTiming
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.readLHS

fun reevaluateTree(
    rows: List<RuleTreeRow>
) {
    val frontMap =
        mutableMapOf<List<Int>, ScoredFront>()

    val failureMap =
        mutableMapOf<List<Int>, String>()

    val level1TestCount =
        rows.count { row ->
            row.rule.size == 1
        }

    for (row in rows) {
        val ruleStartNano =
            System.nanoTime()

        val decoded =
            decodeRule(row)

        val depth =
            decoded.attrs.size

        val ruleLabel =
            readLHS(decoded.attrs)

        val isLevel1 =
            decoded.prefix.isEmpty()

        println(
            "\n$CYAN 🔬 Re-evaluating: " +
                    "$ruleLabel $RESET"
        )

        // ============================================================
        // Parent resolution
        // ============================================================

        val parent =
            resolveParent(
                decoded.prefix,
                frontMap
            )

        // ============================================================
        // Full evolution
        // ============================================================

        val evolutionStartNano =
            System.nanoTime()

        val front =
            fullTopRange(
                attributes =
                    decoded.attrs,
                parentFront =
                    parent.frontForEvolution
            )

        val evolutionSeconds =
            (
                    System.nanoTime() -
                            evolutionStartNano
                    ) / 1_000_000_000.0

        frontMap[decoded.attrs] =
            front

        // ============================================================
        // ROC / AUC validation
        // ============================================================

        val delong =
            if (!isLevel1) {
                val parentScored =
                    parent.scored
                        ?: error(
                            "Missing parent front for " +
                                    ruleLabel
                        )

                DeLong.compare(
                    datasetWithHeader.labels,
                    parentScored.scores,
                    front.scores
                )
            } else {
                null
            }

        val auc =
            if (isLevel1) {
                AUC.compute(
                    datasetWithHeader.labels,
                    front.scores
                )
            } else {
                delong?.auc2
            }

        val randomAucP =
            if (isLevel1) {
                RandomAucBaseline.requireReady()

                empiricalAucPValueGreater(
                    observedAuc =
                        auc
                            ?: error(
                                "Missing level-1 AUC"
                            ),
                    randomAucs =
                        RandomAucBaseline.aucs
                )
            } else {
                null
            }

        val randomAucAdjustedP =
            if (isLevel1) {
                bonferroniCorrect(
                    rawP =
                        randomAucP
                            ?: error(
                                "Missing level-1 random AUC p-value"
                            ),
                    nTests =
                        level1TestCount
                )
            } else {
                null
            }

        val rocPass =
            if (isLevel1) {
                randomAucAdjustedP!! <
                        hp.alphaThreshold
            } else {
                delong!!.pOneSided <
                        hp.alphaThreshold
            }

        val improvement =
            parent.scored?.let {
                frontDistance(
                    it.front,
                    front.front
                )
            } ?: 0.0

        val node =
            recordStep(
                prefix =
                    decoded.prefix,
                addition =
                    decoded.addition,
                scoredFront =
                    front,
                parentScoredFront =
                    parent.scored,
                meta =
                    mapOf(
                        "depth" to depth,
                        "improvement" to improvement,

                        "deLong" to delong,

                        "auc" to auc,
                        "randomAucP" to randomAucP,
                        "randomAucAdjustedP" to
                                randomAucAdjustedP
                    )
            )

        // ============================================================
        // Attribute match
        // ============================================================

        val expectedAttributes =
            decoded.attrs
                .map { columnNames[it] }
                .toSet()

        val actualAttributes =
            extractBarsFromLabel(
                node.label
            ).keys

        val missing =
            expectedAttributes -
                    actualAttributes

        val hasMissing =
            missing.isNotEmpty()

        // ============================================================
        // Distribution distance
        // ============================================================

        val distributionResult =
            if (!hasMissing) {
                smoothedSpearmanPerNode(
                    oldLabel =
                        row.label,
                    newLabel =
                        node.label,
                    threshold =
                        hp.spearmanThreshold,
                    sigma =
                        1.0,
                    radius =
                        2
                )
            } else {
                null
            }

        val distributionDistance =
            distributionResult?.distance

        val distributionThreshold =
            distributionResult?.threshold

        val distributionPass =
            distributionResult?.pass ?: false

        // ============================================================
        // Failure calculation
        // ============================================================

        val baseFailure =
            when {
                hasMissing ->
                    "MISSING:${
                        missing.joinToString(",")
                    }"

                !rocPass ->
                    "ROC_FAIL"

                !distributionPass ->
                    "DIST_FAIL"

                else ->
                    "OK"
            }

        val parentFailure =
            failureMap[decoded.prefix]

        val finalFailure =
            when {
                parentFailure != null &&
                        parentFailure != "OK" ->
                    "PARENT_FAIL"

                else ->
                    baseFailure
            }

        val validated =
            finalFailure == "OK"

        failureMap[decoded.attrs] =
            finalFailure

        // ============================================================
        // Metadata enrichment
        // ============================================================

        val last =
            node.steps.last()

        val enrichedStep =
            last.copy(
                meta =
                    last.meta +
                            mapOf(
                                "validation" to validated,
                                "failure" to finalFailure,

                                "distributionMetric" to
                                        "smooth+spearman",
                                "distributionDistance" to
                                        distributionDistance,
                                "distributionThreshold" to
                                        distributionThreshold,

                                "aucValidationMode" to
                                        if (isLevel1) {
                                            "random-auc-baseline"
                                        } else {
                                            "delong-parent"
                                        },

                                "randomAucP" to
                                        randomAucP,
                                "randomAucAdjustedP" to
                                        randomAucAdjustedP
                            )
            )

        node.steps[node.steps.lastIndex] =
            enrichedStep

        // ============================================================
        // Debug output
        // ============================================================

        if (isLevel1) {
            println(
                "Level-1 random AUC test: " +
                        "auc=${"%.4f".format(auc)} " +
                        "raw p=${"%.4g".format(randomAucP)} " +
                        "adj p=${
                            "%.4g".format(
                                randomAucAdjustedP
                            )
                        } → " +
                        if (rocPass) {
                            "PASS"
                        } else {
                            "FAIL"
                        }
            )
        } else {
            println(
                "ROC p=${delong!!.pOneSided} → " +
                        if (rocPass) {
                            "PASS"
                        } else {
                            "FAIL"
                        }
            )
        }

        if (distributionResult != null) {
            println(
                "SmoothSpearman distance=" +
                        "${distributionResult.distance}, " +
                        "threshold=" +
                        "${distributionResult.threshold} → " +
                        if (distributionPass) {
                            "PASS"
                        } else {
                            "FAIL"
                        }
            )
        }

        if (finalFailure == "PARENT_FAIL") {
            println(
                "⚠️ Inherited failure from parent"
            )
        }

        // ============================================================
        // Progress update
        // ============================================================

        val totalRuleSeconds =
            (
                    System.nanoTime() -
                            ruleStartNano
                    ) / 1_000_000_000.0

        val validationSeconds =
            (
                    totalRuleSeconds -
                            evolutionSeconds
                    ).coerceAtLeast(0.0)

        VALIDATION_PROGRESS.ruleFinished(
            ValidationRuleTiming(
                depth =
                    depth,
                rule =
                    ruleLabel,

                evolutionSeconds =
                    evolutionSeconds,
                validationSeconds =
                    validationSeconds,

                validated =
                    validated,
                failure =
                    finalFailure
            )
        )
    }
}
