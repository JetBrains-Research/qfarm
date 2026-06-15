package org.jetbrains.bio.qfarm.core.init

import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.params.hp

fun AttributeGene.Companion.ofCenterWidth(
    attributeIndex: Int,
    min: Double,
    max: Double,
    cfg: RuleInitConfig,
    center: Double,
    width: Double
): AttributeGene {
    val w = width.coerceIn(MIN_WIDTH, hp.maxWidth)

    val c = center.coerceIn(w / 2.0, 1.0 - w / 2.0)

    val loP = c - w / 2.0
    val hiP = c + w / 2.0

    val lower = cfg.percentile.value(attributeIndex, loP)
    val upper = cfg.percentile.value(attributeIndex, hiP)

    return AttributeGene(
        attributeIndex = attributeIndex,
        lowerBound = lower.coerceAtLeast(min),
        upperBound = upper.coerceAtMost(max),
        min = min,
        max = max,
        pLeft = loP,
        pRight = hiP,
        cfg = cfg
    )
}
