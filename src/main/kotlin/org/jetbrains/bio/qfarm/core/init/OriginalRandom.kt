package org.jetbrains.bio.qfarm.core.init

import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig

fun createOriginalRandomGenes(
    indices: List<Int>,
    cfg: RuleInitConfig
): List<AttributeGene> {
    return indices.map { idx ->
        val min = cfg.bounds[idx][0]
        val max = cfg.bounds[idx][1]
        AttributeGene.of(idx, min, max, cfg)
    }
}
