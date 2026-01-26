package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import java.time.Instant
import java.util.PriorityQueue

data class CachedFront(
    val node: RuleTreeNode,
    val totalArea: Double,
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
)

const val MAX_TOP_FRONTS = 1000

val TOP_FRONTS: PriorityQueue<CachedFront> =
    PriorityQueue(compareBy { it.totalArea }) // min-heap


/* ------------------------- In-memory data model -------------------------- */

/** One DFS step: prefix (before), the new addition, and the resulting front for prefix+addition. */
data class RuleStep(
    val prefix: List<Int>,   // full path BEFORE addition
    val addition: Int,       // the node we add
    val front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    val meta: Map<String, Any?> = emptyMap(),
    val createdAt: Instant = Instant.now()
)

/**
 * Tree node = exactly ONE addition (attribute + range). Root has nulls.
 * Identity uses (additionAttrIndex, additionRange), so different ranges become different nodes.
 * Labeling stays clean (attribute name only).
 */
class RuleTreeNode(
    val additionAttrIndex: Int? = null,                            // null for root
    val depth: Int = 0,
    var frontUrl: String? = null
) {
    /** All steps recorded at THIS node (usually 1, but we allow re-runs). */
    val steps: MutableList<RuleStep> = mutableListOf()
    /** Children in insertion order (DFS order). */
    val children: MutableList<RuleTreeNode> = mutableListOf()
}

/** Root of the tree (empty path). */
val RULE_TREE_ROOT = RuleTreeNode()
lateinit var RULE_JSON_WRITER: RuleTreeJsonWriter

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
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
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

    // ------------------------------------------------------------
    // 2) Decide if this front qualifies for Top-k
    // ------------------------------------------------------------
    val qualifiesForTop =
        deltaArea > 0.0 &&
                (TOP_FRONTS.size < MAX_TOP_FRONTS ||
                        totalArea > TOP_FRONTS.peek().totalArea)

    // ------------------------------------------------------------
    // 3) ONLY IF TOP-k → render + keep
    // ------------------------------------------------------------
    if (qualifiesForTop) {

        val parentFront = EvolutionContext.frontStack
            .dropLast(1)
            .lastOrNull()

        val title =
            "Front shift: ${readLHS(prefix + addition)}\n" +
                    "Δ area = ${"%.4f".format(deltaArea)}"

        val url = renderFrontPlotUrl(
            parentFront,
            front,
            title = title,
            randomFront = false
        )

        if (!url.isNullOrBlank()) {
            additionNode.frontUrl = url
        }

        if (TOP_FRONTS.size == MAX_TOP_FRONTS) {
            val evicted = TOP_FRONTS.poll()

            evicted.node.frontUrl = null
        }

        TOP_FRONTS.add(CachedFront(additionNode, totalArea, front))
    }

    // ------------------------------------------------------------
    // 4) Store step WITHOUT front reference
    // ------------------------------------------------------------
    val step = RuleStep(
        prefix = prefix,
        addition = addition,
        front = ISeq.empty(),
        meta = enrichedMeta
    )

    additionNode.steps += step

    RULE_JSON_WRITER.append(
        prefix = prefix,
        addition = addition,
        depth = additionNode.depth,
        deltaArea = deltaArea.takeIf { it > 0.0 },
        totalArea = totalArea,
        frontUrl = additionNode.frontUrl,
        createdAt = step.createdAt
    )

    return additionNode
}
