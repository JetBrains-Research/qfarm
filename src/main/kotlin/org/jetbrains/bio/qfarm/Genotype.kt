// OK, nothing to do, trivial

package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import io.jenetics.util.Factory

fun createGenotypeFactory(cfg: RuleInitConfig): Factory<Genotype<AttributeGene>> {
    val fixedIndices = cfg.fixedAttributes.map { it.first }.toSet()

    // Pool is strictly the cfg.searchAttributes minus RHS & fixed
    val availableSearch = cfg.searchAttributes
        .asSequence()
        .filter { it != cfg.rightAttrIndex }
        .filter { it !in fixedIndices }
        .toSet()

    val pool = IndexPool(availableSearch)

    return Factory {
        val antecedentChromosome = RuleSideChromosome.of(cfg, pool)
        Genotype.of(antecedentChromosome)
    }
}
