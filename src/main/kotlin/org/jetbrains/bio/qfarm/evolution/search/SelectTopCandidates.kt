package org.jetbrains.bio.qfarm.evolution.search

import org.jetbrains.bio.qfarm.params.GREEN
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.hp

fun selectTopCandidates(
    cheap: List<CheapCandidate>,
    prefix: List<Int>
): List<Int> {

    val beamWidth =
        if (prefix.isEmpty()) hp.maxFirstChildren
        else hp.maxChildren

    val selected = cheap
        .sortedByDescending { it.auc }
        .take(beamWidth)

    println("$GREEN Selected top ${selected.size}/${cheap.size} candidates $RESET")

    return selected.map { it -> it.attr }
}
