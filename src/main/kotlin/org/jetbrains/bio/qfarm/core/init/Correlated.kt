package org.jetbrains.bio.qfarm.core.init

import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.rand
import kotlin.math.pow

fun createPairwiseGenes(
    indices: List<Int>,
    cfg: RuleInitConfig
): List<AttributeGene> {
    val prior = requireNotNull(cfg.pairwisePrior) {
        "PAIRWISE_PERCENTILE init mode requires pairwisePrior"
    }

    val k = indices.size

    repeat(100) {
        val centers = List(k) { rand.nextDouble(0.0, 1.0) }
        val bins = centers.map { prior.binOf(it) }

        val lambda = if (k <= 1) 0.0 else 1.0 / (k - 1)

        var cTotal = 1.0

        for (i in 0 until k) {
            for (j in i + 1 until k) {
                val cij = prior.correction(
                    attrI = indices[i],
                    attrJ = indices[j],
                    binI = bins[i],
                    binJ = bins[j]
                )

                cTotal *= cij.pow(lambda)
            }
        }

        val r = sampleTargetSupportRatio(
            minSupport = hp.minSupport,
            maxSupport = hp.maxSupport,
            datasetSize = datasetWithHeader.data.size
        )

        val productTarget = r / cTotal

        if (productTarget <= 0.0) return@repeat
        if (productTarget > hp.maxWidth.pow(k)) return@repeat

        val widths = sampleWidthsWithProduct(
            k = k,
            productTarget = productTarget,
            maxWidth = hp.maxWidth,
            centers = centers
        ) ?: return@repeat

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

    return createIndependentGenes(indices, cfg)
}
