package org.jetbrains.bio.qfarm.output

import org.jetbrains.bio.qfarm.PLOTS_DIR
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.hp
import java.io.File

fun collectLeaves(root: RuleTreeNode): List<RuleTreeNode> {
    val leaves = mutableListOf<RuleTreeNode>()

    fun dfs(node: RuleTreeNode) {
        if (node.children.isEmpty()) {
            leaves += node
        } else {
            node.children.forEach { dfs(it) }
        }
    }

    dfs(root)
    return leaves
}

fun exportLeafRules(
    root: RuleTreeNode,
    dataset: DatasetWithHeader,
    file: File = File("$PLOTS_DIR/leaf_rules_${hp.runName}.tsv")
) {

    val leaves = collectLeaves(root)

    val allRows = mutableListOf<FullRuleRow>()

    for (leaf in leaves) {

        val step = leaf.steps.lastOrNull() ?: continue
        val fullPath = step.prefix + step.addition

        val scoredFront = leaf.steps.last().scoredFront

        if (scoredFront.front.isEmpty) continue

        val rows = extractFullRows(
            scoredFront,
            dataset,
            step.meta,
            fullPath
        )

        allRows += rows
    }

    writeFullTsv(allRows, file)
}
