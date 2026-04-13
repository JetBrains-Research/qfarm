package org.jetbrains.bio.qfarm.output.fronts

import org.jetbrains.bio.qfarm.PLOTS_DIR
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.output.tree.collectLeaves
import org.jetbrains.bio.qfarm.util.hp
import java.io.File

fun filterLeafRows(
    rows: List<ExportRuleRow>,
    idToNode: Map<Int, RuleTreeNode>,
    root: RuleTreeNode
): List<ExportRuleRow> {

    val leaves = collectLeaves(root).toSet()

    return rows.filter { row ->
        idToNode[row.id] in leaves
    }
}

fun exportAllRuleFormats(root: RuleTreeNode) {

    val (rows, nodeToId) = buildExportRows(root, columnNames)

    val leafRows = filterLeafRows(rows, nodeToId, root)

    writeCsv(rows, File("$PLOTS_DIR/rules_tree_${hp.runName}.csv"))
    writeTxtLight(rows, File("$PLOTS_DIR/rules_tree_light_${hp.runName}.txt"))
    writeTxtMedium(rows, File("$PLOTS_DIR/rules_tree_medium_${hp.runName}.txt"))
    writeTxtHeavy(root, columnNames, File("$PLOTS_DIR/rules_tree_heavy_${hp.runName}.txt"))
    writeTxtTreeInline(
        root,
        rows,
        nodeToId,
        File("$PLOTS_DIR/rules_tree_inline_${hp.runName}.txt")
    )

    writeCsv(leafRows, File("$PLOTS_DIR/rules_leaves_${hp.runName}.csv"))
    writeTxtLight(leafRows, File("$PLOTS_DIR/rules_leaves_light_${hp.runName}.txt"))
    writeTxtMedium(leafRows, File("$PLOTS_DIR/rules_leaves_medium_${hp.runName}.txt"))
//    writeTxtHeavy(root, columnNames, File("$PLOTS_DIR/rules_leaves_heavy_${hp.runName}.txt"))
}
