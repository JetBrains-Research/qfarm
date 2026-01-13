package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import kotlin.Double
import kotlin.String
import kotlin.collections.List

fun readLHS(
    side: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    perc: Boolean = true,
    onlyNames: Boolean = false
): String {
    if (side.isEmpty()) return ""

    val names = columnNames
    val cols  = sortedColumns
    val last  = side.lastIndex

    return buildString {
        for (i in 0..last) {
            val (idx, range) = side[i]
            val name = names.getOrNull(idx) ?: "attr_$idx"

            if (onlyNames) {
                append(name)
            } else if (perc) {
                val pleft  = cumulativePercentage(cols[idx], range.start).toInt()
                val pright = cumulativePercentage(cols[idx], range.endInclusive).toInt()
                append(name).append(" ∈ [")
                    .append(pleft).append(", ").append(pright).append("]%")
            } else {
                append(name).append(" ∈ [")
                    .append(range.start).append(", ")
                    .append(range.endInclusive).append(']')
            }

            if (i < last) append(" AND ")
        }
    }
}


fun compactRuleString(
    colNames: List<String>,
    sortedCols: List<DoubleArray>,
    genotype: Genotype<AttributeGene>,
    colored: Boolean = true,
    includeConsequent: Boolean = false,
    percentilesOnly: Boolean = true
): String {
    val antecedentChromosome = genotype[0] as RuleSideChromosome

    val colorBlue = if (colored) BLUE else ""
    val colorYellow = if (colored) YELLOW else ""
    val colorReset = if (colored) RESET else ""

    fun geneLine(gene: AttributeGene, isConsequent: Boolean): String {
        val name = colNames[gene.attributeIndex]
        val sortedVals = sortedCols[gene.attributeIndex]
        val left = cumulativePercentage(sortedVals, gene.lowerBound).toInt()
        val right = cumulativePercentage(sortedVals, gene.upperBound).toInt()
        val range = right - left

        val percentilesStr =
            if (range < 2 || range > 90) "${colorYellow}($left%,$right%)$colorReset"
            else "($left%,$right%)"

        val body =
            if (percentilesOnly) {
                percentilesStr
            } else {
                "[%.4f, %.4f] from [%.4f, %.4f] %s"
                    .format(gene.lowerBound, gene.upperBound, gene.min, gene.max, percentilesStr)
            }

        return if (isConsequent) "===>  $name $body"
        else "  ${colorBlue}$name$colorReset $body"
    }

    val nonDefaultGenes = antecedentChromosome.filter { !it.isDefault }

    return buildString {
        nonDefaultGenes.forEachIndexed { i, gene ->
            append(geneLine(gene, isConsequent = false))
            if (i < nonDefaultGenes.lastIndex) append('\t')
        }
        if (includeConsequent) {
            appendLine("===>  BC_LDL.direct (90%, 100%)") // kept as-is per TODO
        }
    }.trimEnd()
}

private val ANSI_RE: Regex = Regex("\\u001B\\[[;\\d]*m")

fun stripAnsi(s: String): String = ANSI_RE.replace(s, "")

// choose color by percentile range (0..100)
// here we use the midpoint of [lowerPct, upperPct]
fun colorForPercentiles(lowerPct0: Int, upperPct0: Int): String {
    val lowerPct = lowerPct0.coerceIn(0, 100)
    val upperPct = upperPct0.coerceIn(0, 100)
    val mid = (lowerPct + upperPct) / 2.0
    return when {
        mid < 33.334 -> GREEN     // low
        mid < 66.667 -> YELLOW    // medium
        else         -> PURPLE   // high
    }
}
