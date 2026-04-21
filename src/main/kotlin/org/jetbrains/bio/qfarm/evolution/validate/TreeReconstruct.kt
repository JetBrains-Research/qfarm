package org.jetbrains.bio.qfarm.evolution.validate

import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.frontDistance
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.statistics.delong.DeLong
import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.readLHS

fun reevaluateTree(rows: List<RuleTreeRow>) {

    val frontMap = mutableMapOf<String, ScoredFront>()

    for (row in rows) {

        val decoded = decodeRule(row)

        println("\n$CYAN 🔬 Re-evaluating: ${readLHS(decoded.attrs)} $RESET")

        val parent = resolveParent(
            decoded.prefix,
            decoded.parentKey,
            frontMap
        )

        val front = fullTopRange(
            attributes = decoded.attrs,
            parentFront = parent.frontForEvolution
        )

        val delong = DeLong.compare(
            datasetWithHeader.labels,
            parent.scored.scores,
            front.scores
        )

        val improvement = frontDistance(
            parent.scored.front,
            front.front
        )

        recordStep(
            prefix = decoded.prefix,
            addition = decoded.addition,
            scoredFront = front,
            meta = mapOf(
                "depth" to (decoded.prefix.size + 1),
                "improvement" to improvement,
                "deLong" to delong
            )
        )

        frontMap[decoded.key] = front
    }
}
