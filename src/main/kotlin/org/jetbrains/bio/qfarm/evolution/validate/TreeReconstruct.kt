package org.jetbrains.bio.qfarm.evolution.validate

import org.jetbrains.bio.qfarm.compare.extractBarsFromLabel
import org.jetbrains.bio.qfarm.compare.ksPerNode
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
    val validationMap = mutableMapOf<List<Int>, Boolean>()

    for (row in rows) {

        val decoded = decodeRule(row)

        println("\n$CYAN 🔬 Re-evaluating: ${readLHS(decoded.attrs)} $RESET")

        val parent = resolveParent(
            decoded.prefix,
            frontMap
        )

        val front = fullTopRange(
            attributes = decoded.attrs,
            parentFront = parent.frontForEvolution
        )

        frontMap[decoded.attrs] = front

        // ROC
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
            meta = mapOf(
                "depth" to (decoded.prefix.size + 1),
                "improvement" to improvement,
                "deLong" to delong
            )
        )

        println(row.label)
        println(node.label)

        // ATTRIBUTE MATCH CHECK
        val attrsA = extractBarsFromLabel(row.label).keys
        val attrsB = extractBarsFromLabel(node.label).keys

        val missing = attrsA - attrsB

        if (missing.isNotEmpty()) {
            println("⚠️ Missing attributes: $missing → FAIL")

            validationMap[decoded.attrs] = false
            continue
        }

        // KS CHECK
        val ksFinal = ksPerNode(
            labelA = row.label,
            labelB = node.label
        )

        val ksPass = ksFinal.pValue >= 0.01

        // -----------------------------
        // Validation decision
        // -----------------------------
        val validated = rocPass && ksPass

        validationMap[decoded.attrs] = validated

        println("ROC p=${delong.pOneSided} → ${if (rocPass) "PASS" else "FAIL"}")
        println("KS p=${ksFinal.pValue} → ${if (ksPass) "PASS" else "FAIL"}")
    }
}
