package org.jetbrains.bio.qfarm

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import java.time.Instant

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

/** Chronological log of all steps: (prefix, addition, front(prefix+addition)). */
val STEP_LOG: MutableList<RuleStep> = mutableListOf()

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
    prefix: List<Int>,
    addition: Int,
    front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
    meta: Map<String, Any?> = emptyMap()
): RuleTreeNode {

    val prefixNode = ensurePath(prefix)

    // find/create child using ONLY the attribute index
    val additionNode = prefixNode.children.firstOrNull {
        it.additionAttrIndex == addition
    } ?: RuleTreeNode(addition, prefixNode.depth + 1).also {
        prefixNode.children += it
    }

    val step = RuleStep(prefix = prefix, addition = addition, front = front, meta = meta)
    additionNode.steps += step
    STEP_LOG += step

    val url = meta["frontUrl"]?.toString()
    if (!url.isNullOrBlank()) additionNode.frontUrl = url

    return additionNode
}


/* --------------------------- JSON persistence --------------------------- */

/* --------------------------- Serializable DTOs --------------------------- */

//@Serializable
//data class AttrRangeDTO(
//    val index: Int,
//    val lo: Double,
//    val hi: Double
//)

//@Serializable
//data class SerializableRuleStep(
//    val prefix: List<AttrRangeDTO>,           // full path BEFORE addition
//    val addition: AttrRangeDTO,               // the new node added
//    val meta: Map<String, String?>,           // extra info
//    val createdAt: String,                    // ISO-8601
//    val fitness: List<List<Double>>           // front(prefix+addition): fitness vectors
//)


///** Save the whole STEP_LOG as JSON (portable, readable). */
//fun saveStepLogToJson(path: String, stepLog: List<RuleStep> = STEP_LOG) {
//    val serial = stepLog.map { step ->
//        SerializableRuleStep(
//            prefix = step.prefix.map { p -> AttrRangeDTO(p.index, p.range.start, p.range.endInclusive) },
//            addition = AttrRangeDTO(step.addition.index, step.addition.range.start, step.addition.range.endInclusive),
//            meta = step.meta.mapValues { it.value?.toString() },
//            createdAt = step.createdAt.toString(),
//            fitness = step.front.map { it.fitness().data().toList() }.toList()
//        )
//    }
//
//    val json = Json { prettyPrint = true; encodeDefaults = true }
//        .encodeToString(ListSerializer(SerializableRuleStep.serializer()), serial)
//
//    File(path).writeText(json)
//}
