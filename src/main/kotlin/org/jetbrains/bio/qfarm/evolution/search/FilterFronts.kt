package org.jetbrains.bio.qfarm.evolution.search

import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.util.GREEN
import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.hp

fun filterCandidates(
    candidates: List<CandidateAddition>,
    prefix: List<Int>
): List<CandidateAddition> {

    val isLevel1 = prefix.isEmpty()

    val filtered = candidates.filter { c ->

        val significant = if (isLevel1) {
            c.randomAucPass == true
        } else {
            c.deLong?.pOneSided?.let {
                it < hp.alphaThreshold
            } == true
        }

        val active = isActive(c.front, c.attr)

        if (!significant) {
            if (isLevel1) {
                println(
                    "$RED 🛑 FILTERED OUT ${columnNames[c.attr]}  |  " +
                            "AUC=${"%.4f".format(c.auc)}  |  " +
                            "raw p=${"%.4g".format(c.randomAucP)}  |  " +
                            "Bonferroni p=${"%.4g".format(c.randomAucAdjustedP)} $RESET"
                )
            } else {
                println(
                    "$RED 🛑 FILTERED OUT ${columnNames[c.attr]}  |  " +
                            "p=${"%.4g".format(c.deLong?.pOneSided)} $RESET"
                )
            }
        } else if (!active) {
            println("$RED ⚠️ FILTERED OUT ${columnNames[c.attr]}  |  INACTIVE $RESET")
        }

        significant && active
    }

    println("$GREEN Filtered ${filtered.size}/${candidates.size} $RESET")

    return filtered
}

fun isActive(front: ScoredFront, attr: Int): Boolean {

    val phenotypes = front.front

    for (i in 0 until phenotypes.size()) {

        val genotype = phenotypes[i].genotype()

        val lhs = genotype[0] as RuleSideChromosome
        val nGenes = lhs.length()

        for (j in 0 until nGenes) {
            val gene = lhs[j]

            if (!gene.isDefault && gene.attributeIndex == attr) {
                return true
            }
        }
    }

    return false
}
