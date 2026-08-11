package org.jetbrains.bio.qfarm.core

import io.jenetics.Gene
import io.jenetics.util.RandomRegistry
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.params.hp
import kotlin.math.max
import kotlin.math.min

data class AttributeGene(
    val attributeIndex: Int,
    val lowerBound: Double,
    val upperBound: Double,
    val min: Double,
    val max: Double,
    val pLeft: Double,
    val pRight: Double,
    val cfg: RuleInitConfig
) : Gene<Pair<Double, Double>, AttributeGene> {

    override fun allele(): Pair<Double, Double> = lowerBound to upperBound

    override fun isValid(): Boolean =
                lowerBound in min..upperBound &&
                upperBound <= max &&
                pLeft <= pRight &&
                pLeft >= 0.0 &&
                pRight <= 1.0 &&
                (pRight - pLeft) <= hp.maxWidth

    val isDefault: Boolean
        get() = lowerBound == min && upperBound == max

    override fun newInstance(): AttributeGene {
        val rand = RandomRegistry.random()

        val width = rand.nextDouble(1e-4, hp.maxWidth)
        val center = rand.nextDouble(width / 2.0, 1.0 - width / 2.0)

        val loP = center - width / 2.0
        val hiP = center + width / 2.0

        val lower = cfg.percentile.value(attributeIndex, loP)
        val upper = cfg.percentile.value(attributeIndex, hiP)

        return copy(
            pLeft = loP,
            pRight = hiP,
            lowerBound = lower.coerceAtLeast(min),
            upperBound = upper.coerceAtMost(max)
        )
    }

    override fun newInstance(value: Pair<Double, Double>): AttributeGene {
        val lo = value.first.coerceIn(min, max)
        val hi = value.second.coerceIn(min, max)

        return copy(
            lowerBound = min(lo, hi),
            upperBound = max(lo, hi)
        )
    }

    companion object {
        fun of(attributeIndex: Int, min: Double, max: Double, cfg: RuleInitConfig): AttributeGene {
            return AttributeGene(
                attributeIndex,
                lowerBound = min,
                upperBound = max,
                min = min,
                max = max,
                pLeft = 0.0,
                pRight = 1.0,
                cfg = cfg
            ).newInstance()
        }
    }
}
