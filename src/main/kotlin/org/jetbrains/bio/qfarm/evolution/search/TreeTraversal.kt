package org.jetbrains.bio.qfarm.evolution.search

import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.TOPRULES
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.util.CYAN
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.bio.qfarm.util.readLHS


val USED: MutableSet<Int> = mutableSetOf()

fun treeTraversal(prefix: List<Int>) {

    val locUsed = mutableSetOf<Int>()
    var maxLength = true

    while (prefix.size < hp.maxDepth) {

        // STEP 1: cheap (NOW WITH USED)
        val cheap = evaluateCheapAdditions(prefix, USED)

        if (cheap.isEmpty()) {
            println("$RED Nothing to add anymore!!! $RESET")
            maxLength = false
            break
        }

        // STEP 2: beam
        val selected = selectTopCandidates(cheap, prefix)

        // STEP 3: full eval
        val evaluated = evaluateAllAdditions(
            prefix,
            selected,
            datasetWithHeader
        )

        // STEP 4: filtering
        val candidates = filterCandidates(evaluated, prefix)

        if (candidates.isEmpty()) {
            println("$RED No candidates survived filtering $RESET")
            maxLength = false
            break
        }

        for ((childCount, candidate) in candidates.withIndex()) {

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

                    // depth > 1
                    "deLong" to candidate.deLong,

                    // level 1
                    "auc" to candidate.auc,
                    "randomAucP" to candidate.randomAucP,
                    "randomAucAdjustedP" to candidate.randomAucAdjustedP,
                    "randomAucPass" to candidate.randomAucPass
                )
            )

            USED += attr
            locUsed += attr

            TOPRULES += currentRule

            treeTraversal(currentRule)

            EvolutionContext.frontStack.removeLastOrNull()

        }

        break
    }

    if (maxLength) {
        println("$YELLOW Max depth or branch complete for ${readLHS(prefix)} $RESET")
    }

    USED -= locUsed
}
