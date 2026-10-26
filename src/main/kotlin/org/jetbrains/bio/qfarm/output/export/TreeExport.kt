package org.jetbrains.bio.qfarm.output.export

import kotlinx.serialization.json.*
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.discreteInfo
import org.jetbrains.bio.qfarm.evaluation.fronts.toPFSeries
import org.jetbrains.bio.qfarm.output.logs.RuleStep
import org.jetbrains.bio.qfarm.output.fronts.buildExportRows
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.rightAttrIndex
import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.visualization.attrsToFileName
import java.io.File
import java.security.MessageDigest
import java.time.Instant

const val TREE_JSON_SCHEMA_VERSION = 3

/** Node key: same stem as the front_plots/<key>_pf.html files. */
private fun nodeKey(path: List<Int>): String = attrsToFileName(path)

/** Doubles are written via Double.toString (exact round-trip); non-finite values as strings. */
private fun num(d: Double?): JsonPrimitive =
    if (d == null || d.isFinite()) JsonPrimitive(d) else JsonPrimitive(d.toString())

private fun pathOf(node: RuleTreeNode): List<Int> =
    generateSequence(node) { it.parent }.mapNotNull { it.additionAttrIndex }.toList().reversed()

/**
 * Writes the retained rule tree as JSON Lines, like log.jsonl: one "metadata" line,
 * one "node" line per retained node (with the full Pareto front), one "summary" line.
 */
fun writeTreeJsonl(root: RuleTreeNode, file: File, startedAt: Instant, runtimeSeconds: Double) {
    val labels = datasetWithHeader.labels
    val nPos = labels.count { it == 1 }
    val nIdOf = buildExportRows(root, columnNames).second.entries.associate { (id, n) -> n to id }
    val dataPath = hp.dataPath

    val metadata = buildJsonObject {
        put("type", "metadata")
        put("schema_version", TREE_JSON_SCHEMA_VERSION)
        put("qfarm_commit", gitCommit())
        put("startedAt", startedAt.toString())
        put("parameters", Json { encodeDefaults = true }.encodeToJsonElement(hp))
        put("rhs", buildJsonObject {
            put("attribute", columnNames[rightAttrIndex])
            put("lo", num(rightGene.lowerBound))
            put("hi", num(rightGene.upperBound))
        })
        put("dataset", buildJsonObject {
            put("path", dataPath)
            put("sha256", dataPath?.let { sha256(File(it)) })
            put("n_rows", labels.size)
            put("columns", JsonArray(columnNames.map(::JsonPrimitive)))
            put("n_pos", nPos)
            put("n_neg", labels.size - nPos)
        })
        put("conventions", buildJsonObject {
            put("interval", "closed: lo <= x <= hi (rule conditions and rhs label)")
            put("score_tolerance", num(1e-6))
            put("nan", "rows with NaN RHS are dropped at load; NaN in an LHS attribute used by a rule aborts the run")
            put("roc_scores", "per-row count of covering rules, over the rule set given by parameters.rocComparison")
        })
    }

    file.bufferedWriter().use { w ->
        fun line(o: JsonObject) = w.appendLine(Json.encodeToString(JsonObject.serializer(), o))

        line(metadata)

        fun visit(node: RuleTreeNode) {
            val step = node.steps.lastOrNull()
            if (node.additionAttrIndex != null && step != null) line(nodeJson(node, pathOf(node), step, nIdOf[node]))
            node.children.forEach(::visit)
        }
        visit(root)

        line(buildJsonObject {
            put("type", "summary")
            put("runName", hp.runName)
            put("totalRuntimeSeconds", num(runtimeSeconds))
            put("finishedAt", Instant.now().toString())
        })
    }
}

private fun nodeJson(
    node: RuleTreeNode,
    path: List<Int>,
    step: RuleStep,
    nId: Int?
): JsonObject = buildJsonObject {
    val meta = step.meta
    val deLong = meta["deLong"] as? DeLongResult
    val front = step.scoredFront.front

    put("type", "node")
    put("createdAt", step.createdAt.toString())
    put("key", nodeKey(path))
    put("nID", nId)
    put("parent_key", if (path.size > 1) nodeKey(path.dropLast(1)) else null)
    put("depth", path.size)
    put("attributes", JsonArray(path.map { JsonPrimitive(columnNames[it]) }))
    put("children", JsonArray(node.children.map { JsonPrimitive(nodeKey(path + it.additionAttrIndex!!)) }))
    put("test", if (path.size == 1) "random_auc_baseline" else "delong")
    put("auc", num(meta["auc"] as? Double))
    put("parent_auc", num(deLong?.auc1))
    put("delong", deLong?.let { d ->
        buildJsonObject {
            put("z", num(d.zScore))
            put("p_one_sided", num(d.pOneSided))
            put("p_two_sided", num(d.pTwoSided))
            put("var_parent", num(d.variance1))
            put("var_child", num(d.variance2))
            put("covariance", num(d.covariance))
        }
    } ?: JsonNull)
    put("random_auc_p", num(meta["randomAucP"] as? Double))
    put("random_auc_p_adjusted", num(meta["randomAucAdjustedP"] as? Double))
    put("delta_area", num(meta["deltaArea"] as? Double))
    put("total_area", num(meta["totalArea"] as? Double))
    put("n_rules", front.size())

    val metrics = toPFSeries(front, "Child", datasetWithHeader).metrics!!
    put("rules", buildJsonArray {
        for ((i, pt) in front.withIndex()) {
            val m = metrics[i]
            val fitness = pt.fitness().data()
            val lhs = pt.genotype()[0] as RuleSideChromosome
            add(buildJsonObject {
                put("rule_id", i)
                put("support", m.tp + m.fp)
                put("confidence", num(fitness[1]))
                put("tp", m.tp)
                put("fp", m.fp)
                put("tn", m.tn)
                put("fn", m.fn)
                put("objectives", JsonArray(fitness.map(::num)))
                put("conditions", buildJsonArray {
                    for (g in lhs) if (g != null && !g.isDefault) add(buildJsonObject {
                        put("attribute", columnNames[g.attributeIndex])
                        put("kind", if (discreteInfo.isDiscrete[g.attributeIndex]) "discrete" else "numeric")
                        put("lo", num(g.lowerBound))
                        put("hi", num(g.upperBound))
                        put("p_lo", num(g.pLeft))
                        put("p_hi", num(g.pRight))
                    })
                })
            })
        }
    })
}

private fun sha256(file: File): String? = runCatching {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { s ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

private fun gitCommit(): String? = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 }
}.getOrNull()
