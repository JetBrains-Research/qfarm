package org.jetbrains.bio.qfarm.output.validate

import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.compare.RAMP
import org.jetbrains.bio.qfarm.compare.extractBarsFromLabel
import org.jetbrains.bio.qfarm.output.fronts.pad
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode

fun renderRow(values: List<String>): String {
    return values.zip(COLUMNS).joinToString("") { (v, col) ->
            pad(v, col.width)
    }
}

fun centeredMissingBar(width: Int = 20, text: String = "...MISSING..."): String {
    val textLen = text.length

    val left = (width - textLen) / 2
    val right = width - textLen - left

    return "|" + " ".repeat(left) + text + " ".repeat(right) + "|"
}

fun buildPlotsWithMissing(
    label: String?,
    expectedAttrs: List<String>,
    rowColor: String
): String {

    val current = extractBarsFromLabel(label)

    val parts = mutableListOf<String>()

    for (attr in expectedAttrs) {
        val bar = current[attr]

        if (bar != null) {
            val barStr = "|" + bar.joinToString("") { RAMP[it].toString() } + "|"
            parts += "$attr:$barStr"
        } else {
            val emptyBar = centeredMissingBar()
            parts += "$attr:${Ansi.BG_RED}${Ansi.WHITE}${Ansi.BOLD}$emptyBar${Ansi.RESET}$rowColor"
        }
    }

    return parts.joinToString("  AND  ")
}

fun collectAttrs(node: RuleTreeNode): List<String> {
    val attrs = mutableListOf<String>()
    var current: RuleTreeNode? = node

    while (current != null) {
        val idx = current.additionAttrIndex ?: break
        attrs += columnNames[idx]
        current = current.parent
    }

    return attrs.reversed()
}
