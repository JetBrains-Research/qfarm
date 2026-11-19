package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.Genotype
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.params.StopCriterionParams
import kotlin.math.max
import kotlin.math.min

/** Percentile intervals of one rule, ordered by attribute index. */
class RuleIntervals(
    val attributes: IntArray,
    val lower: DoubleArray,
    val upper: DoubleArray
) {
    init {
        require(attributes.size == lower.size && attributes.size == upper.size) {
            "attributes, lower and upper must have the same length"
        }
    }

    companion object {
        fun of(genotype: Genotype<AttributeGene>): RuleIntervals {
            val chromosome = genotype[0]
            val genes = (0 until chromosome.length())
                .map { chromosome[it] }
                .sortedBy { it.attributeIndex }

            return RuleIntervals(
                attributes = IntArray(genes.size) { genes[it].attributeIndex },
                lower = DoubleArray(genes.size) { genes[it].pLeft },
                upper = DoubleArray(genes.size) { genes[it].pRight }
            )
        }
    }
}

/** Intersection over union of the intervals [aLo, aHi] and [bLo, bHi]. */
fun intervalOverlap(aLo: Double, aHi: Double, bLo: Double, bHi: Double): Double {
    val intersection = (min(aHi, bHi) - max(aLo, bLo)).coerceAtLeast(0.0)
    val union = (aHi - aLo) + (bHi - bLo) - intersection

    if (union <= 0.0) {
        // Both intervals have zero width.
        return if (aLo == bLo && aHi == bHi) 1.0 else 0.0
    }

    return intersection / union
}

/**
 * Smallest per-attribute interval overlap of two rules, so two rules are only
 * "the same" when every attribute overlaps. Rules on different attributes have
 * similarity 0.
 */
fun ruleSimilarity(a: RuleIntervals, b: RuleIntervals): Double {
    if (!a.attributes.contentEquals(b.attributes)) return 0.0

    var smallest = 1.0
    for (i in a.attributes.indices) {
        smallest = min(
            smallest,
            intervalOverlap(a.lower[i], a.upper[i], b.lower[i], b.upper[i])
        )
    }
    return smallest
}

/**
 * Stops when the survivor set stops receiving new rules.
 *
 * A survivor is new when no rule in the survivor sets of the previous
 * `patience` generations is similar to it (`ruleSimilarity >= similarityThreshold`).
 * A generation is stagnant when at most `maxNewSurvivors` survivors are new.
 * The criterion fires once `patience` generations in a row are stagnant and
 * the generation is at least `minGenerations`.
 *
 * Comparing with several past survivor sets, not only the previous one,
 * keeps a rule that NSGA-II crowding dropped for a generation or two from
 * counting as new when it comes back.
 *
 * Signal: number of new survivors in the generation (all survivors in the first one).
 */
class SurvivorNoveltyCriterion(
    private val params: StopCriterionParams
) : StopCriterion {

    override val name: String = "survivor-novelty"
    override val signalName: String = "newSurvivors"

    private val history = ArrayDeque<List<RuleIntervals>>()
    private var stagnantStreak = 0

    override fun update(
        generation: Long,
        survivors: ISeq<RulePhenotype>,
        population: ISeq<RulePhenotype>
    ): StopObservation =
        updateIntervals(
            generation,
            survivors.asList().map { RuleIntervals.of(it.genotype()) }
        )

    fun updateIntervals(
        generation: Long,
        survivors: List<RuleIntervals>
    ): StopObservation {
        // The first generation has nothing to compare with, so it is never stagnant.
        val hasReference = history.isNotEmpty()
        val newCount = survivors.count { !seenRecently(it) }

        history.addLast(survivors)
        while (history.size > params.patience) history.removeFirst()

        stagnantStreak =
            if (hasReference && newCount <= params.maxNewSurvivors) stagnantStreak + 1 else 0

        val shouldStop =
            generation >= params.minGenerations && stagnantStreak >= params.patience

        return StopObservation(
            signal = newCount.toDouble(),
            shouldStop = shouldStop
        )
    }

    private fun seenRecently(rule: RuleIntervals): Boolean =
        history.any { past ->
            past.any { ruleSimilarity(rule, it) >= params.similarityThreshold }
        }
}
