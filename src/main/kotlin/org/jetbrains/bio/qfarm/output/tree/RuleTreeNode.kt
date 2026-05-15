package org.jetbrains.bio.qfarm.output.tree

import org.jetbrains.bio.qfarm.output.logs.RuleStep
import org.jetbrains.bio.qfarm.visualization.RenderedFrontPlots

/**Tree node = exactly ONE addition (attribute + range). Root has nulls.*/
class RuleTreeNode(
    val additionAttrIndex: Int? = null,
    val depth: Int = 0,
    var parent: RuleTreeNode? = null,
    var plots: RenderedFrontPlots? = null,
    var label: String? = null
) {
    val steps: MutableList<RuleStep> = mutableListOf()
    val children: MutableList<RuleTreeNode> = mutableListOf()
}
