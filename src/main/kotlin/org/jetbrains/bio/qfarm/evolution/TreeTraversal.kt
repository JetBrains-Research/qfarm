package org.jetbrains.bio.qfarm.evolution

import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.TOPRULES
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.output.recordStep
import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.bio.qfarm.util.readLHS


val VISITED_SETS: MutableSet<Set<Int>> = mutableSetOf()

fun treeTraversal(prefix: List<Int>) {

    var maxLength = true

    while (prefix.size < hp.maxDepth) {

        // STEP 1: cheap
        val cheap = evaluateCheapAdditions(prefix)

        if (cheap.isEmpty()) {
            println("$RED Nothing to add anymore!!! $RESET")
            maxLength = false
            break
        }

        // STEP 2: beam
        val selected = selectTopCandidates(cheap, prefix)
        val selectedAttrs = selected

        // STEP 3: full eval (reuse existing)
        val evaluated = evaluateAllAdditions(
            prefix,
            selectedAttrs,
            datasetWithHeader
        )

        // STEP 4: filtering
        val candidates = filterCandidates(evaluated)

        if (candidates.isEmpty()) {
            println("$RED No candidates survived filtering $RESET")
            maxLength = false
            break
        }

        var childCount = 0

        for (candidate in candidates) {

            val attr = candidate.attr

            val currentRule = prefix + attr

            println("\n $CYAN → Exploring child ${childCount + 1}: ${readLHS(currentRule)} $RESET")

            EvolutionContext.frontStack.addLast(candidate.front)

            recordStep(
                prefix = prefix,
                addition = attr,
                scoredFront = candidate.front,
                meta = mapOf(
                    "depth" to (prefix.size + 1),
                    "improvement" to candidate.improvement,
                    "pValue" to candidate.pValue,
                    "aucParent" to candidate.aucParent,
                    "aucChild" to candidate.aucChild
                )
            )

            VISITED_SETS += currentRule.toSet()
            TOPRULES += currentRule

            treeTraversal(currentRule)

            EvolutionContext.frontStack.removeLastOrNull()

            childCount++
        }

        break
    }

    // TODO: separate cases
    if (maxLength) {
        println("$YELLOW Max depth or branch complete for ${readLHS(prefix)} $RESET")
    }

}
