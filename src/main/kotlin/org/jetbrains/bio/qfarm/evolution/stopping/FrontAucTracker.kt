package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.statistics.delong.AUC

/**
 * The conditions of one rule (non-default genes): attribute indices and closed bounds.
 * Lists, so that equal rules are equal keys.
 */
internal data class RuleKey(val attributes: List<Int>, val lows: List<Double>, val ups: List<Double>)

internal fun ruleKey(pt: RulePhenotype): RuleKey {
    val lhs = pt.genotype()[0] as RuleSideChromosome
    val attributes = ArrayList<Int>()
    val lows = ArrayList<Double>()
    val ups = ArrayList<Double>()
    for (i in 0 until lhs.length()) {
        val g = lhs[i]
        if (!g.isDefault) {
            attributes += g.attributeIndex
            lows += g.lowerBound
            ups += g.upperBound
        }
    }
    return RuleKey(attributes, lows, ups)
}

/**
 * ANALYSIS ONLY (temporary, see [org.jetbrains.bio.qfarm.params.StopCriterionParams.aucEveryGenerations]).
 *
 * Own-front AUC of an evolution's fronts, computed after the evolution has ended, so it is
 * not part of the evolution's time. Row score = number of front rules covering the row, with
 * the same closed intervals and tolerance as `computeFrontScores`, so the values equal the
 * AUC QFARM reports for a front.
 *
 * Fronts must be passed in generation order: the coverage of a rule is reused from the
 * previous front, because most rules stay on the front for many generations.
 */
internal class FrontAucTracker(env: EvolutionEnvironment) {

    private val data = env.datasetWithHeader.data
    private val labels = env.datasetWithHeader.labels
    private var previous = HashMap<RuleKey, IntArray>()

    /** False when the dataset has no 0/1 labels for every row (AUC is then undefined). */
    val usable: Boolean = labels.size == data.size

    fun auc(front: List<RuleKey>): Double? {
        if (!usable || front.isEmpty()) return null

        val current = HashMap<RuleKey, IntArray>(front.size * 2)
        val counts = IntArray(data.size)
        for (rule in front) {
            val covered = current.getOrPut(rule) { previous[rule] ?: coverage(rule) }
            for (r in covered) counts[r]++
        }
        previous = current

        return AUC.compute(labels, DoubleArray(counts.size) { counts[it].toDouble() })
    }

    /** Rows covered by the rule; same test as `computeFrontScores`. */
    private fun coverage(rule: RuleKey): IntArray {
        val eps = 1e-6
        val k = rule.attributes.size
        val idxs = rule.attributes.toIntArray()
        val lows = rule.lows.toDoubleArray()
        val ups = rule.ups.toDoubleArray()
        val rows = ArrayList<Int>()

        for (r in data.indices) {
            val row = data[r]
            var covered = true
            var j = 0
            while (j < k) {
                val v = row[idxs[j]]
                if (v.isNaN()) {
                    error("NaN encountered in dataset at row=$r column=${idxs[j]}. NaNs not supported yet.")
                }
                if (v < lows[j] - eps || v > ups[j] + eps) {
                    covered = false
                    break
                }
                j++
            }
            if (covered) rows += r
        }
        return rows.toIntArray()
    }

    companion object {
        fun keys(front: ISeq<RulePhenotype>): List<RuleKey> = front.asList().map(::ruleKey)
    }
}
