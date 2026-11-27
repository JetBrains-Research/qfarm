package org.jetbrains.bio.qfarm.output.tree

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.AttributeGene

object NodeLabeler {

    /**
     * Node label: one coverage bar per attribute, built from the node's label front
     * (Pareto front of parent ∪ child). Computed directly from the rules; no plot is written
     * or read back for it.
     *
     * Bars are listed in the order the attributes were added during search (root → node).
     * The front itself gives no stable order: attributes appear there by first occurrence
     * across rules, and a rule omits attributes left at their full range.
     */
    fun buildLabel(node: RuleTreeNode, labelFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>): String {
        if (node.additionAttrIndex == null) return "START"

        val bars = buildBarsFromFront(labelFront)
        if (bars.isEmpty()) {
            println("[NodeLabeler] No bars for node ${node.additionAttrIndex}")
            return ""
        }

        val searchOrder = pathAttributeNames(node)
        val ordered = bars.entries.sortedBy { (attr, _) ->
            searchOrder.indexOf(attr).let { if (it < 0) Int.MAX_VALUE else it }
        }

        return ordered.joinToString("\n") { (attr, bar) ->
            "${attr}:\n$bar"
        }
    }

    /** Attribute names on the path root → node, in the order they were added. */
    private fun pathAttributeNames(node: RuleTreeNode): List<String> =
        generateSequence(node) { it.parent }
            .mapNotNull { it.additionAttrIndex }
            .map { columnNames.getOrNull(it) ?: "attr#$it" }
            .toList()
            .asReversed()
}
