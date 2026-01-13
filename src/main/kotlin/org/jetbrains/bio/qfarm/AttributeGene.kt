package org.jetbrains.bio.qfarm

import io.jenetics.Gene
import kotlin.math.max
import kotlin.math.min

data class AttributeGene(
    val attributeIndex: Int,
    val lowerBound: Double,
    val upperBound: Double,
    val min: Double,
    val max: Double,
    private val cfg: RuleInitConfig = init_cfg
) : Gene<Pair<Double, Double>, AttributeGene> {

    override fun allele(): Pair<Double, Double> = lowerBound to upperBound

    override fun isValid(): Boolean =
        lowerBound <= upperBound && lowerBound >= min && upperBound <= max

    val isDefault: Boolean
        get() = lowerBound == min && upperBound == max && attributeIndex !in cfg.fixedAttributes.map{it -> it.first}

    override fun newInstance(): AttributeGene {
        val p1 = rand.nextDouble()
        val p2 = rand.nextDouble()
        val loP = min(p1, p2)
        val hiP = max(p1, p2)

        val lower = cfg.percentile.value(attributeIndex, loP)
        val upper = cfg.percentile.value(attributeIndex, hiP)

        return copy(
            lowerBound = lower.coerceAtLeast(min),
            upperBound = upper.coerceAtMost(max)
        )
    }

    override fun newInstance(value: Pair<Double, Double>): AttributeGene =
        copy(lowerBound = value.first, upperBound = value.second)

    companion object {
        fun of(attributeIndex: Int, min: Double, max: Double, cfg: RuleInitConfig): AttributeGene {
            return AttributeGene(attributeIndex, min, max, min, max, cfg).newInstance()
        }

        fun default(attributeIndex: Int, min: Double, max: Double, cfg: RuleInitConfig): AttributeGene {
            return AttributeGene(attributeIndex, min, max, min, max, cfg)
        }
    }

    fun descript(): String {
        return "Gene of attribute ${columnNames[attributeIndex]} with index $attributeIndex in range ${lowerBound.roundTo(4)} to ${upperBound.roundTo(4)} with min=$min and max=$max"
    }
}
