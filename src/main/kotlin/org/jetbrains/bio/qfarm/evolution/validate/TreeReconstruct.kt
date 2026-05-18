package org.jetbrains.bio.qfarm.evolution.validate

import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.compare.extractBarsFromLabel
import org.jetbrains.bio.qfarm.compare.smoothedSpearmanPerNode
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.frontDistance
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.bio.qfarm.util.readLHS

fun reevaluateTree(rows: List<RuleTreeRow>) {

    val frontMap = mutableMapOf<List<Int>, ScoredFront>()
    val failureMap = mutableMapOf<List<Int>, String>()

    for (row in rows) {

        val decoded = decodeRule(row)

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
        val delong = DeLong.compare(
            datasetWithHeader.labels,
            parent.scored.scores,
            front.scores
        )

        val rocPass = delong.pOneSided < hp.alphaThreshold

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
                "deLong" to delong
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
                threshold = 0.20,
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
                "distributionThreshold" to distThreshold
            )
        )

        node.steps[node.steps.lastIndex] = enrichedStep

        // -----------------------------
        // DEBUG OUTPUT
        // -----------------------------
        println("ROC p=${delong.pOneSided} → ${if (rocPass) "PASS" else "FAIL"}")

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
