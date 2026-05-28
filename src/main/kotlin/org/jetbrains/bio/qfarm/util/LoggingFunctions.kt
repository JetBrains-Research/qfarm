package org.jetbrains.bio.qfarm.util

import io.jenetics.Genotype
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.params.BLUE
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.YELLOW
import kotlin.String
import kotlin.collections.List
import kotlin.math.roundToInt

fun readLHS(
    side: List<Int>
): String {
    if (side.isEmpty()) return ""

    val names = columnNames
    val last  = side.lastIndex

    return buildString {
        for (i in 0..last) {
            val idx = side[i]
            val name = names.getOrNull(idx) ?: "attr_$idx"

            append(name)
            if (i < last) append(" AND ")
        }
    }
}


fun compactRuleString(
    colNames: List<String>,
    genotype: Genotype<AttributeGene>,
    colored: Boolean = true,
    includeConsequent: Boolean = false,
    percentilesOnly: Boolean = true
): String {
    val antecedentChromosome = genotype[0] as RuleSideChromosome

    val colorBlue = if (colored) BLUE else ""
    val colorYellow = if (colored) YELLOW else ""
    val colorReset = if (colored) RESET else ""

    fun pct(p: Double): Int = (p * 100).roundToInt()

    fun geneLine(gene: AttributeGene, isConsequent: Boolean): String {
        val name = colNames[gene.attributeIndex]

        val left = pct(gene.pLeft)
        val right = pct(gene.pRight)
        val range = right - left

        val percentilesStr =
            if (range !in 2..90) {
                "${colorYellow}($left%,$right%)$colorReset"
            } else {
                "($left%,$right%)"
            }

        val body =
            if (percentilesOnly) {
                percentilesStr
            } else {
                "[%.4f, %.4f] from [%.4f, %.4f] %s"
                    .format(
                        gene.lowerBound,
                        gene.upperBound,
                        gene.min,
                        gene.max,
                        percentilesStr
                    )
            }

        return if (isConsequent) {
            "===>  $name $body"
        } else {
            "  ${colorBlue}$name$colorReset $body"
        }
    }

    val nonDefaultGenes = antecedentChromosome.filter { !it.isDefault }

    return buildString {
        nonDefaultGenes.forEachIndexed { i, gene ->
            append(geneLine(gene, isConsequent = false))
            if (i < nonDefaultGenes.lastIndex) append('\t')
        }

        if (includeConsequent) {
            appendLine("===>  BC_LDL.direct (90%, 100%)")
        }
    }.trimEnd()
}

private val ANSI_RE: Regex = Regex("\\u001B\\[[;\\d]*m")

fun stripAnsi(s: String): String = ANSI_RE.replace(s, "")

// ---------------------- Plot: SupportX–Confidence combined view ----------------------
fun numericRuleString(
    header: List<String>,
    gt: Genotype<AttributeGene>,
    multiLine: Boolean = true
): String {
    val lhs = gt[0] as RuleSideChromosome
    val active = lhs.asSequence()
        .filterNotNull()
        .filter { !it.isDefault }
        .toList()

    if (active.isEmpty()) return "(no antecedent)"

    val separator = if (multiLine) "\n" else " AND "

    val parts = active.mapNotNull { g ->
        val name = header.getOrNull(g.attributeIndex) ?: "attr_${g.attributeIndex}"
        val lb = String.format("%.3f", g.lowerBound)
        val ub = String.format("%.3f", g.upperBound)

        if (multiLine) {
            // ORIGINAL (unchanged)
            "• $name ∈ \n [$lb, $ub]"
        } else {
            // NEW: inequalities + omit defaults
            when {
                g.lowerBound > g.min && g.upperBound < g.max ->
                    "$lb ≤ $name ≤ $ub"
                g.lowerBound > g.min ->
                    "$name ≥ $lb"
                g.upperBound < g.max ->
                    "$name ≤ $ub"
                else ->
                    null // skip full-range
            }
        }
    }

    if (parts.isEmpty()) return "(no antecedent)"

    val body = parts.joinToString(separator)

    return if (multiLine) {
        "Numeric rule:\n$body"
    } else {
        body
    }
}
