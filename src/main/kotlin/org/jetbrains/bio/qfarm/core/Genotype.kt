package org.jetbrains.bio.qfarm.core

import io.jenetics.Genotype
import io.jenetics.util.Factory
import org.jetbrains.bio.qfarm.evolution.IndexPool
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig

fun createGenotypeFactory(cfg: RuleInitConfig): Factory<Genotype<AttributeGene>> {
    val fixedIndices = cfg.fixedAttributes.toSet()

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
