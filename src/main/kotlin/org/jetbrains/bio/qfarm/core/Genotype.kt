package org.jetbrains.bio.qfarm.core

import io.jenetics.Genotype
import io.jenetics.util.Factory
import org.jetbrains.bio.qfarm.evolution.IndexPool
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig

fun createIndexPool(cfg: RuleInitConfig): IndexPool {
    val fixedIndices = cfg.fixedAttributes.toSet()

    val availableSearch = cfg.searchAttributes
        .asSequence()
        .filter { it != cfg.rightAttrIndex }
        .filter { it !in fixedIndices }
        .toSet()

    return IndexPool(availableSearch)
}

fun createGenotypeFactory(
    cfg: RuleInitConfig,
    indexPool: IndexPool = createIndexPool(cfg)
): Factory<Genotype<AttributeGene>> {
    return Factory {
        val antecedentChromosome = RuleSideChromosome.of(cfg, indexPool)
        Genotype.of(antecedentChromosome)
    }
}
