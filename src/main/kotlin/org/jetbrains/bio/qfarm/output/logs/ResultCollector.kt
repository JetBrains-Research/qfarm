package org.jetbrains.bio.qfarm.output.logs

import org.jetbrains.bio.qfarm.RULE_JSON_WRITER
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.output.tree.NodeLabeler
import org.jetbrains.bio.qfarm.output.tree.RULE_TREE_ROOT
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.util.combinedParetoFront
import org.jetbrains.bio.qfarm.util.readLHS
import org.jetbrains.bio.qfarm.visualization.RenderedFrontPlots
import org.jetbrains.bio.qfarm.visualization.renderFrontPlots
import java.time.Instant

/* ------------------------- In-memory data model -------------------------- */

/** One DFS step: prefix (before), the new addition, and the resulting front for prefix+addition. */
data class RuleStep(
    val prefix: List<Int>,   // full path BEFORE addition
    val addition: Int,       // the node we add
    val scoredFront: ScoredFront,
    val meta: Map<String, Any?> = emptyMap(),
    val createdAt: Instant = Instant.now()
)

/* ----------------------------- Recording API ---------------------------- */

/** Ensure the chain of nodes for a FULL path (list of additions = attr+range at each depth). */
fun ensurePath(prefix: List<Int>): RuleTreeNode {
    var node = RULE_TREE_ROOT
    var d = 0

    for (ar in prefix) {
        d += 1

        val found = node.children.firstOrNull {
            it.additionAttrIndex == ar
        }

        node = if (found != null) {
            found
        } else {
            val created = RuleTreeNode(ar, d).also {
                it.parent = node
            }
            node.children += created
            created
        }
    }

    return node
}

/** Plots rendered ahead of [recordStep] (in parallel for the children of one node). null value = rendering failed. */
class PrerenderedPlots(val plots: RenderedFrontPlots?)

/** The meta [recordStep] stores and passes to the plots: adds deltaArea, totalArea, pValue and auc. */
private fun enrichMeta(prefixNode: RuleTreeNode, meta: Map<String, Any?>): Map<String, Any?> {
    val deltaArea = meta["improvement"]?.toString()?.toDoubleOrNull() ?: 0.0
    val parentTotal = prefixNode.steps.lastOrNull()?.meta?.get("totalArea")?.toString()?.toDoubleOrNull() ?: 0.0
    val deLong = meta["deLong"] as? DeLongResult
    return meta + mapOf(
        "deltaArea" to deltaArea,
        "totalArea" to parentTotal + deltaArea,
        "pValue" to (deLong?.pOneSided ?: meta["randomAucAdjustedP"]),
        "auc" to (deLong?.auc2 ?: meta["auc"])
    )
}

private fun stepTitle(prefix: List<Int>, addition: Int, deltaArea: Double) =
    "Front shift: ${readLHS(prefix + addition)}\n" + "Δ area = ${"%.4f".format(deltaArea)}"

/**
 * Renders the plots of one step without touching the rule tree, so the children of a node can be
 * rendered in parallel before they are recorded one by one. The prefix node must already exist.
 */
fun renderStepPlots(
    prefix: List<Int>,
    addition: Int,
    scoredFront: ScoredFront,
    parentScoredFront: ScoredFront?,
    meta: Map<String, Any?>
): PrerenderedPlots {
    val enrichedMeta = enrichMeta(ensurePath(prefix), meta)
    val deltaArea = enrichedMeta["deltaArea"] as Double
    return PrerenderedPlots(
        renderFrontPlots(
            parentScoredFront = parentScoredFront,
            childScoredFront = scoredFront,
            attrs = prefix + addition,
            deLong = meta["deLong"] as? DeLongResult,
            title = stepTitle(prefix, addition, deltaArea),
            meta = enrichedMeta
        )
    )
}

fun recordStep(
    prefix: List<Int>,
    addition: Int,
    scoredFront: ScoredFront,
    parentScoredFront: ScoredFront? = null,
    meta: Map<String, Any?> = emptyMap(),
    prerendered: PrerenderedPlots? = null
): RuleTreeNode {

    val prefixNode = ensurePath(prefix)

    val additionNode = prefixNode.children.firstOrNull {
        it.additionAttrIndex == addition
    } ?: RuleTreeNode(addition, prefixNode.depth + 1).also {
        it.parent = prefixNode
        prefixNode.children += it
    }

    // ------------------------------------------------------------
    // 1) Compute metrics
    // ------------------------------------------------------------
    val deltaArea = meta["improvement"]?.toString()?.toDoubleOrNull() ?: 0.0

    val parentTotal =
        prefixNode.steps.lastOrNull()
            ?.meta
            ?.get("totalArea")
            ?.toString()
            ?.toDoubleOrNull()
            ?: 0.0

    val totalArea = parentTotal + deltaArea
    val deLong = meta["deLong"] as? DeLongResult

    val enrichedMeta = meta + mapOf(
        "deltaArea" to deltaArea,
        "totalArea" to totalArea,
        "pValue" to (deLong?.pOneSided
            ?: meta["randomAucAdjustedP"]),
        "auc" to (deLong?.auc2 ?: meta["auc"])
    )

    // ------------------------------------------------------------
    // 2) Render using explicit parent if provided,
    //    otherwise fall back to EvolutionContext as before
    // ------------------------------------------------------------
    val parentFront =
        parentScoredFront
            ?: EvolutionContext.frontStack
                .dropLast(1)
                .lastOrNull()

    val title =
        "Front shift: ${readLHS(prefix + addition)}\n" +
                "Δ area = ${"%.4f".format(deltaArea)}"

    val rendered = if (prerendered != null) prerendered.plots else renderFrontPlots(
        parentScoredFront = parentFront,
        childScoredFront = scoredFront,
        attrs = prefix + addition,
        deLong = deLong,
        title = title,
        meta = enrichedMeta
    )

    if (rendered != null) {
        additionNode.plots = rendered
    }
    // ------------------------------------------------------------
    // 3) Store step
    // ------------------------------------------------------------
    val step = RuleStep(
        prefix = prefix,
        addition = addition,
        scoredFront = scoredFront,
        meta = enrichedMeta
    )

    additionNode.steps += step
    // label from the parent ∪ child Pareto front, computed directly (no plot written and parsed back)
    additionNode.label = NodeLabeler.buildLabel(additionNode, combinedParetoFront(parentFront, scoredFront))

    RULE_JSON_WRITER.append(
        prefix = prefix,
        addition = addition,
        depth = additionNode.depth,
        deltaArea = deltaArea,
        totalArea = totalArea,
        deLong = deLong,
        label = additionNode.label,
        frontUrl = additionNode.plots?.combinedUrl,
        createdAt = step.createdAt
    )

    return additionNode
}
