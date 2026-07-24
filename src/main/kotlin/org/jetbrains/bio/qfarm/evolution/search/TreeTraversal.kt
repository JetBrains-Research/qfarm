package org.jetbrains.bio.qfarm.evolution.search

import org.jetbrains.bio.qfarm.PROGRESS
import org.jetbrains.bio.qfarm.params.RED
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.TOPRULES
import org.jetbrains.bio.qfarm.params.YELLOW
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.logger.NodeTiming
import org.jetbrains.bio.qfarm.output.logs.recordStep
import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.readLHS


val USED: MutableSet<Int> = mutableSetOf()

fun treeTraversal(prefix: List<Int>) {
    val locallyUsed = mutableSetOf<Int>()
    var branchCompletedNormally = true

    while (prefix.size < hp.maxDepth) {
        val expansionStartNano = System.nanoTime()

        /*
         * The depth of the prefix being expanded.
         *
         * ROOT        -> 0
         * [X1]        -> 1
         * [X1, X2]    -> 2
         */
        val prefixDepth = prefix.size

        val prefixLabel =
            if (prefix.isEmpty()) {
                "ROOT"
            } else {
                readLHS(prefix)
            }

        // ============================================================
        // STEP 1: cheap evolutions for all possible additions
        // ============================================================

        val cheapStartNano = System.nanoTime()

        val cheap = evaluateCheapAdditions(
            prefix = prefix,
            used = USED
        )

        val cheapSeconds =
            (
                    System.nanoTime() -
                            cheapStartNano
                    ) / 1_000_000_000.0

        /*
         * The prefix expansion was still attempted, even if no
         * candidate additions were available.
         *
         * Reporting zero surviving children allows ProgressLogger to
         * remove the complete assumed descendant subtree.
         */
        if (cheap.isEmpty()) {
            val totalSeconds =
                (
                        System.nanoTime() -
                                expansionStartNano
                        ) / 1_000_000_000.0

            val overheadSeconds =
                (
                        totalSeconds -
                                cheapSeconds
                        ).coerceAtLeast(0.0)

            PROGRESS.nodeFinished(
                timing = NodeTiming(
                    prefixDepth = prefixDepth,
                    rule = prefixLabel,

                    cheapCandidates = 0,
                    fullCandidates = 0,
                    survivingChildren = 0,

                    cheapSeconds = cheapSeconds,
                    fullSeconds = 0.0,
                    overheadSeconds = overheadSeconds
                )
            )

            println(
                "$RED Nothing to add anymore!!! $RESET"
            )

            branchCompletedNormally = false
            break
        }

        // ============================================================
        // STEP 2: select candidates for full evolution
        // ============================================================

        val selected = selectTopCandidates(
            cheap = cheap,
            prefix = prefix
        )

        /*
         * This case may be possible if the beam limit is zero or the
         * selection logic rejects every cheap candidate.
         */
        if (selected.isEmpty()) {
            val totalSeconds =
                (
                        System.nanoTime() -
                                expansionStartNano
                        ) / 1_000_000_000.0

            val overheadSeconds =
                (
                        totalSeconds -
                                cheapSeconds
                        ).coerceAtLeast(0.0)

            PROGRESS.nodeFinished(
                timing = NodeTiming(
                    prefixDepth = prefixDepth,
                    rule = prefixLabel,

                    cheapCandidates = cheap.size,
                    fullCandidates = 0,
                    survivingChildren = 0,

                    cheapSeconds = cheapSeconds,
                    fullSeconds = 0.0,
                    overheadSeconds = overheadSeconds
                )
            )

            println(
                "$RED No candidates selected for full evaluation $RESET"
            )

            branchCompletedNormally = false
            break
        }

        // ============================================================
        // STEP 3: full evolutions for selected additions
        // ============================================================

        val fullStartNano = System.nanoTime()

        val evaluated = evaluateAllAdditions(
            prefix = prefix,
            searchAttributes = selected,
            dataset = datasetWithHeader
        )

        val fullSeconds =
            (
                    System.nanoTime() -
                            fullStartNano
                    ) / 1_000_000_000.0

        // ============================================================
        // STEP 4: filter fully evaluated candidates
        // ============================================================

        val candidates = filterCandidates(
            candidates = evaluated,
            prefix = prefix
        )

        val totalSeconds =
            (
                    System.nanoTime() -
                            expansionStartNano
                    ) / 1_000_000_000.0

        val overheadSeconds =
            (
                    totalSeconds -
                            cheapSeconds -
                            fullSeconds
                    ).coerceAtLeast(0.0)

        /*
         * The progress update happens only after the complete prefix
         * expansion has finished and the actual number of surviving
         * children is known.
         */
        PROGRESS.nodeFinished(
            timing = NodeTiming(
                prefixDepth = prefixDepth,
                rule = prefixLabel,

                cheapCandidates = cheap.size,
                fullCandidates = selected.size,
                survivingChildren = candidates.size,

                cheapSeconds = cheapSeconds,
                fullSeconds = fullSeconds,
                overheadSeconds = overheadSeconds
            )
        )

        if (candidates.isEmpty()) {
            println(
                "$RED No candidates survived filtering $RESET"
            )

            branchCompletedNormally = false
            break
        }

        // ============================================================
        // STEP 5: recursively explore surviving children
        // ============================================================

        for ((childIndex, candidate) in candidates.withIndex()) {
            val attr = candidate.attr
            val currentRule = prefix + attr

            println(
                "\n$CYAN → Exploring child ${childIndex + 1}: " +
                        "${readLHS(currentRule)} $RESET"
            )

            EvolutionContext.frontStack.addLast(
                candidate.front
            )

            try {
                recordStep(
                    prefix = prefix,
                    addition = attr,
                    scoredFront = candidate.front,
                    meta = mapOf(
                        "depth" to currentRule.size,
                        "improvement" to candidate.improvement,

                        // depth > 1
                        "deLong" to candidate.deLong,

                        // level 1
                        "auc" to candidate.auc,
                        "randomAucP" to candidate.randomAucP,
                        "randomAucAdjustedP" to
                                candidate.randomAucAdjustedP,
                        "randomAucPass" to
                                candidate.randomAucPass
                    )
                )

                USED += attr
                locallyUsed += attr

                TOPRULES += currentRule

                treeTraversal(currentRule)
            } finally {
                EvolutionContext.frontStack.removeLastOrNull()
            }
        }

        /*
         * A prefix is expanded only once.
         */
        break
    }

    if (branchCompletedNormally) {
        println(
            "$YELLOW Max depth or branch complete for " +
                    "${readLHS(prefix)} $RESET"
        )
    }

    USED -= locallyUsed
}
