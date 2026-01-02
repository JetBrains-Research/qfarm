package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import java.time.Instant
import java.io.File
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.ListSerializer

/* --------------------------- Serializable DTOs --------------------------- */

@Serializable
data class AttrRangeDTO(
    val index: Int,
    val lo: Double,
    val hi: Double
)

@Serializable
data class SerializableRuleStep(
    val prefix: List<AttrRangeDTO>,           // full path BEFORE addition
    val addition: AttrRangeDTO,               // the new node added
    val meta: Map<String, String?>,           // extra info
    val createdAt: String,                    // ISO-8601
    val fitness: List<List<Double>>           // front(prefix+addition): fitness vectors
)

/* ------------------------- In-memory data model -------------------------- */

data class AttrRange(val index: Int, val range: ClosedFloatingPointRange<Double>)

/** One DFS step: prefix (before), the new addition, and the resulting front for prefix+addition. */
data class RuleStep(
    val prefix: List<AttrRange>,   // full path BEFORE addition
    val addition: AttrRange,       // the node we add
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
    var additionRange: ClosedFloatingPointRange<Double>? = null,   // null for root
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

/** Chronological log of all steps: (prefix, addition, front(prefix+addition)). */
val STEP_LOG: MutableList<RuleStep> = mutableListOf()

/* ----------------------------- Recording API ---------------------------- */

/** Ensure the chain of nodes for a FULL path (list of additions = attr+range at each depth). */
fun ensurePath(prefix: List<AttrRange>): RuleTreeNode {
    var node = RULE_TREE_ROOT
    var d = 0
    for (ar in prefix) {
        d += 1
        // find existing child with SAME attribute index (ignore range)
        val found = node.children.firstOrNull {
            it.additionAttrIndex == ar.index
        }
        node = if (found != null) found else {
            // we can still store the first-seen range on the node; identity is by index only
            val created = RuleTreeNode(ar.index, ar.range, d)
            node.children += created
            created
        }
    }
    return node
}

/**
 * Record one step of the search:
 *   (prefix, addition) -> front(prefix+addition)
 *
 * This builds/locates the prefix chain, ensures the child node for `addition`,
 * attaches the step there, and appends to the chronological STEP_LOG.
 *
 * @return the node representing `addition` under `prefix`.
 */
fun recordStep(
    prefixRanges: List<Pair<Int, ClosedFloatingPointRange<Double>>>,
    addition: Pair<Int, ClosedFloatingPointRange<Double>>,
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    meta: Map<String, Any?> = emptyMap()
): RuleTreeNode {
    val prefix = prefixRanges.map { AttrRange(it.first, it.second) }
    val add    = AttrRange(addition.first, addition.second)

    val prefixNode = ensurePath(prefix)

    // find/create child using ONLY the attribute index
    val additionNode = prefixNode.children.firstOrNull {
        it.additionAttrIndex == add.index
    } ?: RuleTreeNode(add.index, add.range, prefixNode.depth + 1).also {
        prefixNode.children += it
    }

    val step = RuleStep(prefix = prefix, addition = add, front = front, meta = meta)
    additionNode.steps += step
    STEP_LOG += step

    // optionally update node’s stored range & url to the latest
    if (additionNode.additionRange == null) {
        // keep first range if you prefer; otherwise comment this out to always overwrite
        additionNode.additionRange = add.range
    }
    val url = meta["frontUrl"]?.toString()
    if (!url.isNullOrBlank()) additionNode.frontUrl = url

    return additionNode
}

/* ---------------------------- DOT Visualization ------------------------- */

/**
 * Export the rule tree to Graphviz DOT.
 * Each node = one addition (attribute label; tooltip shows range).
 * Edges connect prefix → addition, preserving DFS insertion order.
 */
fun toDOTFromTrie(
    root: RuleTreeNode = RULE_TREE_ROOT,
    header: List<String> = columnNames,
    title: String = "Rule Search Tree (prefix + addition)"
): String {
    val sb = StringBuilder()
    sb.appendLine("digraph G {")
    sb.appendLine("""  label="$title"; labelloc="t"; fontsize=18;""")
    sb.appendLine("""  rankdir=TB; splines=true; overlap=false;""")
    sb.appendLine("""  node [shape=box, style="rounded,filled", fillcolor="#f9f9f9", color="#cccccc", fontsize=11];""")
    sb.appendLine("""  edge [color="#999999", arrowsize=0.6];""")

    var nextId = 0
    fun newId() = "n${nextId++}"
    fun esc(s: String) = s.replace("\"", "\\\"")

    fun nodeLabel(n: RuleTreeNode): String =
        n.additionAttrIndex?.let { idx -> header.getOrNull(idx) ?: "attr#$idx" } ?: "START"

    fun tooltip(n: RuleTreeNode): String {
        val name = nodeLabel(n)
        val lastRange = n.steps.lastOrNull()?.addition?.range
        val r = lastRange ?: n.additionRange
        val imp = n.steps.lastOrNull()
            ?.meta
            ?.get("improvement")
            ?.toString()
            ?.toDoubleOrNull()

        return if (imp != null) "$name\nΔFront area = ${"%.4f".format(imp)}" else name
    }

    // -------- rank-based intensity per node --------

    // Collect all nodes & raw improvements (this part is unchanged)
    val allNodes = mutableListOf<RuleTreeNode>()
    fun collectNodes(n: RuleTreeNode) {
        allNodes += n
        n.children.forEach(::collectNodes)
    }
    collectNodes(root)

    val nodeImprovement: Map<RuleTreeNode, Double> = allNodes.associateWith { n ->
        n.steps.lastOrNull()
            ?.meta
            ?.get("improvement")
            ?.toString()
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: 0.0
    }

// NEW: compute cumulative improvement = sum(parent + self) along each path
    val cumulativeImprovement = mutableMapOf<RuleTreeNode, Double>()
    fun computeCumulative(n: RuleTreeNode, parentCum: Double) {
        val own = nodeImprovement[n] ?: 0.0
        val cum = parentCum + own
        cumulativeImprovement[n] = cum
        n.children.forEach { child ->
            computeCumulative(child, cum)
        }
    }
    computeCumulative(root, 0.0)

    // Linear normalization based on actual cumulative improvement
    val positiveValues = cumulativeImprovement.values.filter { it > 0.0 }
    val minImp = positiveValues.minOrNull() ?: 0.0
    val maxImp = positiveValues.maxOrNull() ?: 0.0
    val impRange = (maxImp - minImp).takeIf { it > 0.0 } ?: 1.0

    fun improvementIntensity(n: RuleTreeNode): Double {
        val v = cumulativeImprovement[n] ?: 0.0
        if (v <= 0.0) return 0.0
        return ((v - minImp) / impRange).coerceIn(0.0, 1.0)
    }


    // Interpolate between *very pale* and *strong* orange, with 50% opacity
    fun orangeFor(node: RuleTreeNode): String {
        if (node.additionAttrIndex == null) {
            // root: light blue at 50% opacity
            return "#EEF6FF80"
        }
        val t = improvementIntensity(node)

        // light -> strong orange
        val r0 = 0xFF; val g0 = 0xFB; val b0 = 0xF2   // almost white warm
        val r1 = 0xFF; val g1 = 0x8C; val b1 = 0x00   // strong orange

        fun lerp(a: Int, b: Int) = (a + (t * (b - a)).toInt()).coerceIn(0, 255)

        val r = lerp(r0, r1)
        val g = lerp(g0, g1)
        val b = lerp(b0, b1)

        // 80 = 50% alpha
        return String.format("#%02X%02X%02X80", r, g, b)
    }

    // ----------------------------------------------------

    fun walk(node: RuleTreeNode, id: String = newId()): String {
        val isRoot = node.additionAttrIndex == null
        val fill  = if (isRoot) "#EEF6FF80" else orangeFor(node)
        val color = if (isRoot) "#4B8AE6" else "#cccccc"
        val label = esc(nodeLabel(node))
        val tip   = esc(tooltip(node))

        val url = node.frontUrl
            ?.let(::esc)

        val urlAttr = if (url != null) """ , URL="$url", target="_blank" """ else ""
        sb.appendLine("""  $id [label="$label", tooltip="$tip", fillcolor="$fill", color="$color"$urlAttr];""")

        for (child in node.children) {
            val cid = newId()
            val childId = walk(child, cid)
            sb.appendLine("  $id -> $childId;")
        }
        return id
    }

    walk(root)
    sb.appendLine("}")
    return sb.toString()
}

/* --------------------------- JSON persistence --------------------------- */

/** Save the whole STEP_LOG as JSON (portable, readable). */
fun saveStepLogToJson(path: String, stepLog: List<RuleStep> = STEP_LOG) {
    val serial = stepLog.map { step ->
        SerializableRuleStep(
            prefix = step.prefix.map { p -> AttrRangeDTO(p.index, p.range.start, p.range.endInclusive) },
            addition = AttrRangeDTO(step.addition.index, step.addition.range.start, step.addition.range.endInclusive),
            meta = step.meta.mapValues { it.value?.toString() },
            createdAt = step.createdAt.toString(),
            fitness = step.front.map { it.fitness().data().toList() }.toList()
        )
    }

    val json = Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(ListSerializer(SerializableRuleStep.serializer()), serial)

    File(path).writeText(json)
}
