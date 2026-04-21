package org.jetbrains.bio.qfarm.evolution.validate

import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.fullTopRange
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.readLHS

fun reevaluateTree(rows: List<RuleTreeRow>) {

    for (row in rows) {

        val attrs = row.rule.map { name ->
            columnNames.indexOf(name).also {
                require(it >= 0) { "Unknown attribute $name" }
            }
        }

        val prefix = attrs.dropLast(1)
        val addition = attrs.last()

        println("\n$CYAN 🔬 Re-evaluating: ${readLHS(attrs)} $RESET")

        val front = fullTopRange(attrs)

        EvolutionContext.frontStack.addLast(front)

        recordStep(
            prefix = prefix,
            addition = addition,
            scoredFront = front,
            meta = emptyMap() // later: validation stats
        )
    }
}
