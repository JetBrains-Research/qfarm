package org.jetbrains.bio.qfarm.output.logs

import org.jetbrains.bio.qfarm.RULE_JSON_WRITER
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.output.tree.NodeLabeler
import org.jetbrains.bio.qfarm.output.tree.RULE_TREE_ROOT
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.util.readLHS
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

fun recordStep(
    prefix: List<Int>,
    addition: Int,
    scoredFront: ScoredFront,
    meta: Map<String, Any?> = emptyMap()
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
        "pValue" to deLong?.pOneSided,
        "auc" to deLong?.auc2
    )

    // ------------------------------------------------------------
    // 2) ALWAYS render (no top-k filtering)
    // ------------------------------------------------------------
    val parentFront = EvolutionContext.frontStack
        .dropLast(1)
        .lastOrNull()

    val title =
        "Front shift: ${readLHS(prefix + addition)}\n" +
                "Δ area = ${"%.4f".format(deltaArea)}"

    val rendered = renderFrontPlots(
        parentScoredFront = parentFront,
        childScoredFront = scoredFront,
        attrs = prefix + addition,
        deLong = deLong,
        title = title
    )

    if (rendered != null) {
        additionNode.plots = rendered
    }

    // ------------------------------------------------------------
    // 3) Store step WITHOUT front reference
    // TODO: why not store front? Does it slow down / cause crash-down?
    // ------------------------------------------------------------
    val step = RuleStep(
        prefix = prefix,
        addition = addition,
        scoredFront = scoredFront,
        meta = enrichedMeta
    )

    additionNode.steps += step
    additionNode.label = NodeLabeler.buildLabel(additionNode)

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
