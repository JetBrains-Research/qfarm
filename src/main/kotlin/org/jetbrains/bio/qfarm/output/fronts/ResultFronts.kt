package org.jetbrains.bio.qfarm.output.fronts

import org.jetbrains.bio.qfarm.OUTPUT
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode

fun exportAllRuleFormats(root: RuleTreeNode) {
    val (rows, _) = buildExportRows(root, columnNames)

    writeCsv(rows, OUTPUT.rulesTreeCsv)
    writeTxtLight(rows, OUTPUT.rulesTreeTxt)
}
