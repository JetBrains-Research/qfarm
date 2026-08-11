package org.jetbrains.bio.qfarm.core

import io.jenetics.Genotype
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.evolution.IndexPool
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig

fun normalizeSeedGenotype(
    genotype: Genotype<AttributeGene>,
    cfg: RuleInitConfig,
    indexPool: IndexPool
): Genotype<AttributeGene> {
    val oldLhs = genotype[0] as RuleSideChromosome
    val byAttr = mutableMapOf<Int, AttributeGene>()

    for (i in 0 until oldLhs.length()) {
        val g = oldLhs[i].copy(cfg = cfg)
        byAttr[g.attributeIndex] = g
    }

    for (idx in cfg.fixedAttributes) {
        val min = cfg.bounds[idx][0]
        val max = cfg.bounds[idx][1]
        val existing = byAttr[idx]

        byAttr[idx] =
            if (existing == null || existing.isDefault) {
                AttributeGene.of(idx, min, max, cfg)
            } else {
                existing
            }
    }

    val genes = mutableListOf<AttributeGene>()

    for (idx in cfg.fixedAttributes) {
        genes += byAttr.getValue(idx)
    }

    for ((idx, gene) in byAttr) {
        if (idx !in cfg.fixedAttributes && idx != cfg.rightAttrIndex) {
            genes += gene
        }
    }

    return Genotype.of(
        RuleSideChromosome(
            ISeq.of(genes),
            cfg,
            indexPool
        )
    )
}
