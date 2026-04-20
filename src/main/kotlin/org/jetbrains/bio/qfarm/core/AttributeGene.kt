package org.jetbrains.bio.qfarm.core

import io.jenetics.Gene
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.rand
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
        lowerBound <= upperBound && lowerBound >= min && upperBound <= max

    val isDefault: Boolean
        get() = lowerBound == min && upperBound == max

    override fun newInstance(): AttributeGene {
        val p1 = rand.nextDouble()
        val p2 = rand.nextDouble()

        val loP = min(p1, p2)
        val hiP = max(p1, p2)

        val lower = cfg.percentile.value(attributeIndex, loP)
        val upper = cfg.percentile.value(attributeIndex, hiP)

        return copy(
            pLeft = loP,
            pRight = hiP,
            lowerBound = lower.coerceAtLeast(min),
            upperBound = upper.coerceAtMost(max)
        )
    }

    override fun newInstance(value: Pair<Double, Double>): AttributeGene =
        copy(lowerBound = value.first, upperBound = value.second)

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
