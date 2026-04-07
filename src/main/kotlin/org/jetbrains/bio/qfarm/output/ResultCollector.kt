package org.jetbrains.bio.qfarm.output

import org.jetbrains.bio.qfarm.RULE_JSON_WRITER
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.util.readLHS
import org.jetbrains.bio.qfarm.visualization.renderFrontPlotUrl
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
        // find existing child with SAME attribute index (ignore range)
        val found = node.children.firstOrNull {
            it.additionAttrIndex == ar
        }
        node = if (found != null) found else {
            // we can still store the first-seen range on the node; identity is by index only
            val created = RuleTreeNode(ar, d)
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

    val enrichedMeta = meta + mapOf(
        "deltaArea" to deltaArea,
        "totalArea" to totalArea
    )

    val deLong = meta["deLong"] as? DeLongResult

    // ------------------------------------------------------------
    // 2) ALWAYS render (no top-k filtering)
    // ------------------------------------------------------------
    val parentFront = EvolutionContext.frontStack
        .dropLast(1)
        .lastOrNull()

    val title =
        "Front shift: ${readLHS(prefix + addition)}\n" +
                "Δ area = ${"%.4f".format(deltaArea)}"

    val url = renderFrontPlotUrl(
        parentFront,
        scoredFront,
        attrs = prefix + addition,
        title = title,
        randomFront = false
    )

    if (!url.isNullOrBlank()) {
        additionNode.frontUrl = url
    }

    additionNode.label = NodeLabeler.buildLabel(additionNode)

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

    RULE_JSON_WRITER.append(
        prefix = prefix,
        addition = addition,
        depth = additionNode.depth,
        deltaArea = deltaArea,
        totalArea = totalArea,

        deLong = deLong,

        label = additionNode.label,
        frontUrl = additionNode.frontUrl,
        createdAt = step.createdAt
    )

    return additionNode
}
