package org.jetbrains.bio.qfarm.evolution.validate

import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.compare.extractBarsFromLabel
import org.jetbrains.bio.qfarm.compare.smoothedSpearmanPerNode
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.random.RandomAucBaseline
import org.jetbrains.bio.qfarm.evaluation.random.bonferroniCorrect
import org.jetbrains.bio.qfarm.evaluation.random.empiricalAucPValueGreater
import org.jetbrains.bio.qfarm.evaluation.frontDistance
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.readLHS

fun reevaluateTree(rows: List<RuleTreeRow>) {

    val frontMap = mutableMapOf<List<Int>, ScoredFront>()
    val failureMap = mutableMapOf<List<Int>, String>()

    val level1TestCount = rows.count { row ->
        row.rule.size == 1
    }

    for (row in rows) {

        val decoded = decodeRule(row)
        val isLevel1 = decoded.prefix.isEmpty()

        println("\n$CYAN 🔬 Re-evaluating: ${readLHS(decoded.attrs)} $RESET")

        // -----------------------------
        // Resolve parent (for evolution + ROC)
        // -----------------------------
        val parent = resolveParent(decoded.prefix, frontMap)

        val front = fullTopRange(
            attributes = decoded.attrs,
            parentFront = parent.frontForEvolution
        )

        frontMap[decoded.attrs] = front

        // -----------------------------
        // ROC (DeLong)
        // -----------------------------
        val delong = if (!isLevel1) {
            DeLong.compare(
                datasetWithHeader.labels,
                parent.scored.scores,
                front.scores
            )
        } else {
            null
        }

        val auc = if (isLevel1) {
            AUC.compute(datasetWithHeader.labels, front.scores)
        } else {
            delong?.auc2
        }

        val randomAucP = if (isLevel1) {
            RandomAucBaseline.requireReady()

            empiricalAucPValueGreater(
                observedAuc = auc ?: error("Missing level-1 AUC"),
                randomAucs = RandomAucBaseline.aucs
            )
        } else {
            null
        }

        val randomAucAdjustedP = if (isLevel1) {
            bonferroniCorrect(
                rawP = randomAucP ?: error("Missing level-1 random AUC p-value"),
                nTests = level1TestCount
            )
        } else {
            null
        }

        val rocPass = if (isLevel1) {
            randomAucAdjustedP!! < hp.alphaThreshold
        } else {
            delong!!.pOneSided < hp.alphaThreshold
        }

        val improvement = frontDistance(
            parent.scored.front,
            front.front
        )

        val node = recordStep(
            prefix = decoded.prefix,
            addition = decoded.addition,
            scoredFront = front,
            parentScoredFront = parent.scored,
            meta = mapOf(
                "depth" to (decoded.prefix.size + 1),
                "improvement" to improvement,

                // depth > 1
                "deLong" to delong,

                // level 1
                "auc" to auc,
                "randomAucP" to randomAucP,
                "randomAucAdjustedP" to randomAucAdjustedP
            )
        )

        // -----------------------------
        // ATTRIBUTE MATCH CHECK
        // -----------------------------
        val attrsA = decoded.attrs.map { columnNames[it] }.toSet()
        val attrsB = extractBarsFromLabel(node.label).keys

        val missing = attrsA - attrsB
        val hasMissing = missing.isNotEmpty()

        // -----------------------------
        // DISTRIBUTION DISTANCE CHECK
        // -----------------------------
        val distFinal = if (!hasMissing) {
            smoothedSpearmanPerNode(
                oldLabel = row.label,
                newLabel = node.label,
                threshold = hp.spearmanThreshold,
                sigma = 1.0,
                radius = 2
            )
        } else null

        val distDistance = distFinal?.distance
        val distThreshold = distFinal?.threshold
        val distPass = distFinal?.pass ?: false

        // -----------------------------
        // BASE FAILURE (local)
        // -----------------------------
        val baseFailure = when {
            hasMissing -> "MISSING:${missing.joinToString(",")}"
            !rocPass -> "ROC_FAIL"
            !distPass -> "DIST_FAIL"
            else -> "OK"
        }

        // -----------------------------
        // PARENT FAILURE PROPAGATION (transitive)
        // -----------------------------
        val parentFailure = failureMap[decoded.prefix]

        val finalFailure = when {
            parentFailure != null && parentFailure != "OK" -> "PARENT_FAIL"
            else -> baseFailure
        }

        val validated = finalFailure == "OK"

        failureMap[decoded.attrs] = finalFailure

        // -----------------------------
        // UPDATE META (immutable-safe)
        // -----------------------------
        val last = node.steps.last()

        val enrichedStep = last.copy(
            meta = last.meta + mapOf(
                "validation" to validated,
                "failure" to finalFailure,

                "distributionMetric" to "smooth+spearman",
                "distributionDistance" to distDistance,
                "distributionThreshold" to distThreshold,

                "aucValidationMode" to if (isLevel1) "random-auc-baseline" else "delong-parent",
                "randomAucP" to randomAucP,
                "randomAucAdjustedP" to randomAucAdjustedP
            )
        )

        node.steps[node.steps.lastIndex] = enrichedStep

        // -----------------------------
        // DEBUG OUTPUT
        // -----------------------------
        if (isLevel1) {
            println(
                "Level-1 random AUC test: auc=${"%.4f".format(auc)} " +
                        "raw p=${"%.4g".format(randomAucP)} " +
                        "adj p=${"%.4g".format(randomAucAdjustedP)} → " +
                        if (rocPass) "PASS" else "FAIL"
            )
        } else {
            println("ROC p=${delong!!.pOneSided} → ${if (rocPass) "PASS" else "FAIL"}")
        }

        if (distFinal != null) {
            println(
                "SmoothSpearman distance=${distFinal.distance}, " +
                        "threshold=${distFinal.threshold} → " +
                        if (distPass) "PASS" else "FAIL"
            )
        }

        if (finalFailure == "PARENT_FAIL") {
            println("⚠️ Inherited failure from parent")
        }
    }
}
