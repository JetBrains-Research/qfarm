package org.jetbrains.bio.qfarm.output.tree

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.compactRuleString
import org.jetbrains.bio.qfarm.util.stripAnsi
import kotlin.math.roundToInt
import kotlin.math.max

private val RULE_PCT_REGEX =
    Regex("""([^()\n]+?)\s*\(\s*(\d{1,3})\s*%\s*,\s*(\d{1,3})\s*%\s*\)""")

data class Interval(
    val attr: String,
    val lo: Int,
    val hi: Int
)

/** Parse rulePct strings that may contain MULTIPLE attributes */
fun parseIntervals(rulePcts: List<String>): List<Interval> =
    rulePcts.flatMap { text ->
        val cleaned = text.trim()

        RULE_PCT_REGEX.findAll(cleaned).map { m ->
            val attr = m.groupValues[1].trim()
            val lo = m.groupValues[2].toInt()
            val hi = m.groupValues[3].toInt()
            Interval(attr, lo, hi)
        }.toList()
    }


/** Unicode ramp */
private const val RAMP = " ▁▂▃▄▅▆▇█"

/** Map [0,1] → character */
private fun coverageChar(c: Double): Char {
    val idx = (c.coerceIn(0.0, 1.0) * (RAMP.length - 1)).roundToInt()
    return RAMP[idx]
}

/**
 * Build 20-bin boxed coverage bars per attribute.
 * Normalized to max (shape-based).
 */
fun buildCoverageBars(
    intervals: List<Interval>,
    bins: Int = 20
): Map<String, String> {

    val byAttr = intervals.groupBy { it.attr }
    val result = mutableMapOf<String, String>()

    for ((attr, ranges) in byAttr) {

        val coverages = DoubleArray(bins)

        for (r in ranges) {
            val lo = r.lo.toDouble()
            val hi = r.hi.toDouble()
            if (hi <= lo) continue

            val width = hi - lo
            val nSamples = max(20, width.roundToInt())

            for (k in 0 until nSamples) {
                val x = lo + (hi - lo) * k / (nSamples - 1)
                val bin = ((x / 100.0) * bins)
                    .toInt()
                    .coerceIn(0, bins - 1)
                coverages[bin] += 1.0
            }
        }

        val maxCov = coverages.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val bar = buildString {
            for (i in 0 until bins) {
                append(coverageChar(coverages[i] / maxCov))
            }
        }

        result[attr] = "|$bar|"
    }

    return result
}

/**
 * Coverage bars computed directly from a front, without writing and re-reading a plot.
 * Uses the same rule strings and support filter as the Pareto-front plot's points
 * (see buildParetoFrontPlotCombined); it replaced parsing those strings back out of the saved plot HTML.
 */
fun buildBarsFromFront(front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>): Map<String, String> {
    val rulePcts = front.asList().mapNotNull { pt ->
        val support = pt.fitness().data()[0]
        if (support < hp.minSupport || support > hp.maxSupport) return@mapNotNull null
        stripAnsi(compactRuleString(columnNames, pt.genotype())).split(")").joinToString(")\n")
    }
    return buildCoverageBars(parseIntervals(rulePcts))
}
