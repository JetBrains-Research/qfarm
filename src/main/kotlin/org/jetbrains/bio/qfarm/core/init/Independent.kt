package org.jetbrains.bio.qfarm.core.init

import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.rand
import kotlin.math.pow

fun createIndependentGenes(
    indices: List<Int>,
    cfg: RuleInitConfig,
): List<AttributeGene> {
    val k = indices.size

    val r = sampleTargetSupportRatio(
        minSupport = hp.minSupport,
        maxSupport = hp.maxSupport,
        datasetSize = datasetWithHeader.data.size
    )

    val centers = List(k) { rand.nextDouble(0.0, 1.0) }

    val widths = sampleWidthsWithProduct(
        k = k,
        productTarget = r,
        maxWidth = hp.maxWidth,
        centers = centers
    ) ?: run {
        val fallback = r.pow(1.0 / k).coerceIn(MIN_WIDTH, hp.maxWidth)
        List(k) { fallback }
    }

    return indices.mapIndexed { i, idx ->
        val min = cfg.bounds[idx][0]
        val max = cfg.bounds[idx][1]

        AttributeGene.ofCenterWidth(
            attributeIndex = idx,
            min = min,
            max = max,
            cfg = cfg,
            center = centers[i],
            width = widths[i]
        )
    }
}
