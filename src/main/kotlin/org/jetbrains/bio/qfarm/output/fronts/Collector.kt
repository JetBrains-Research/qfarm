package org.jetbrains.bio.qfarm.output.fronts

import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.params.hp
import kotlin.collections.component1
import kotlin.collections.component2

data class ExportRuleRow(
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    val rule: String,
    val pValue: Double?,
    val auc: Double?,
    val area: Double?,
    val label: String?
)

fun buildExportRows(
    root: RuleTreeNode,
    header: List<String>
): Pair<
        List<ExportRuleRow>,
        Map<Int, RuleTreeNode>
        > {

    val rows = mutableListOf<ExportRuleRow>()
    val nodeToId = mutableMapOf<RuleTreeNode, Int>()

    var nextId = 1

    fun getAttrName(idx: Int?): String =
        idx?.let { header.getOrNull(it) ?: "attr#$it" } ?: "ROOT"

    fun buildRule(node: RuleTreeNode): String {
        val path = mutableListOf<String>()
        var current: RuleTreeNode? = node

        while (current != null && current.additionAttrIndex != null) {
            path += getAttrName(current.additionAttrIndex)
            current = current.parent
        }

        val rhs = "${hp.rightAttribute} ∈ [${formatNumber(rightGene.lowerBound)}, ${formatNumber(rightGene.upperBound)}]"

        return path.reversed().joinToString(" ∧ ") + " -> $rhs"
    }

    fun dfs(node: RuleTreeNode) {

        val id = nextId++
        nodeToId[node] = id

        val step = node.steps.lastOrNull()
        val meta = step?.meta

        val row = ExportRuleRow(
            id = id,
            parentId = node.parent?.let { nodeToId[it] },
            depth = node.depth,
            rule = if (node.additionAttrIndex == null) "START"
            else buildRule(node),
            pValue = meta?.get("pValue") as? Double,
            auc = meta?.get("auc") as? Double,
            area = meta?.get("totalArea") as? Double,
            label = node.label
        )

        rows += row

        node.children.forEach { dfs(it) }
    }

    dfs(root)
    return rows to nodeToId.entries.associate { (k, v) -> v to k }
}
