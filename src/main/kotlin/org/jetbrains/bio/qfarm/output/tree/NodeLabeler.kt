package org.jetbrains.bio.qfarm.output.tree

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene

object NodeLabeler {

    /**
     * Node label: one coverage bar per attribute, built from the node's label front
     * (Pareto front of parent ∪ child). Computed directly from the rules; no plot is written
     * or read back for it.
     */
    fun buildLabel(node: RuleTreeNode, labelFront: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>): String {
        if (node.additionAttrIndex == null) return "START"

        val bars = buildBarsFromFront(labelFront)
        if (bars.isEmpty()) {
            println("[NodeLabeler] No bars for node ${node.additionAttrIndex}")
            return ""
        }

        return bars.entries.joinToString("\n") { (attr, bar) ->
            "${attr}:\n$bar"
        }
    }
}
