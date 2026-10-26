package org.jetbrains.bio.qfarm.analyze

import kotlinx.serialization.json.*
import org.jetbrains.bio.qfarm.output.export.sha256
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.util.loadNumericDataset
import java.io.File

class Condition(val attribute: String, val lo: Double, val hi: Double)

class Rule(val id: Int, val tp: Int, val fp: Int, val conditions: List<Condition>)

class TreeNode(
    val key: String,
    val nId: Int?,
    val parentKey: String?,
    val depth: Int,
    val attributes: List<String>,
    val children: List<String>,
    val auc: Double?,
    val rules: List<Rule>
)

class TreeRun(val meta: JsonObject, val nodes: List<TreeNode>)

private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull
private val JsonElement?.dbl get() = str?.toDoubleOrNull()

fun loadTreeJsonl(file: File): TreeRun {
    val lines = file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    val meta = lines.firstOrNull { it["type"].str == "metadata" } ?: error("$file has no metadata line")
    val nodes = lines.filter { it["type"].str == "node" }.map { o ->
        TreeNode(
            key = o["key"].str!!,
            nId = o["nID"].str?.toInt(),
            parentKey = o["parent_key"].str,
            depth = o["depth"].str!!.toInt(),
            attributes = o["attributes"]!!.jsonArray.map { it.str!! },
            children = o["children"]!!.jsonArray.map { it.str!! },
            auc = o["auc"].dbl,
            rules = o["rules"]!!.jsonArray.map { r ->
                val ro = r.jsonObject
                Rule(
                    id = ro["rule_id"].str!!.toInt(),
                    tp = ro["tp"].str!!.toInt(),
                    fp = ro["fp"].str!!.toInt(),
                    conditions = ro["conditions"]!!.jsonArray.map { c ->
                        val co = c.jsonObject
                        Condition(co["attribute"].str!!, co["lo"].dbl!!, co["hi"].dbl!!)
                    }
                )
            }
        )
    }
    require(nodes.isNotEmpty()) { "$file contains no nodes" }
    return TreeRun(meta, nodes)
}

/** Per-donor scores: scores[j][i] = number of rules of node j covering donor i. */
class NodeScores(
    val nodes: List<TreeNode>,
    val donorIds: IntArray,
    val y: BooleanArray,
    val scores: List<IntArray>
) {
    val n get() = y.size
    fun indicesOf(positive: Boolean) = y.indices.filter { y[it] == positive }.toIntArray()
}

/**
 * Loads the dataset as `search` did (same column filtering, rows with NaN RHS dropped, closed RHS interval)
 * and checks that it matches the run recorded in tree.jsonl.
 */
fun computeScores(run: TreeRun, dataPath: String, out: File): NodeScores {
    val meta = run.meta
    val rhs = meta["rhs"]!!.jsonObject
    val ds = meta["dataset"]!!.jsonObject
    val excluded = meta["parameters"]?.jsonObject?.get("excludedColumns")?.jsonArray?.map { it.str!! }.orEmpty()

    val expectedHash = ds["sha256"].str
    val hash = sha256(File(dataPath))
    if (expectedHash != null && hash != expectedHash) {
        println("WARNING: $dataPath differs from the run's dataset (sha256 $hash vs $expectedHash)")
    }

    val data = loadNumericDataset(dataPath, excluded.toSet())
    val col = data.header.withIndex().associate { (i, h) -> h to i }
    val rhsIdx = col[rhs["attribute"].str] ?: error("RHS column ${rhs["attribute"].str} not in $dataPath")
    val lo = rhs["lo"].dbl!!
    val hi = rhs["hi"].dbl!!

    val kept = data.data.indices.filter { !data.data[it][rhsIdx].isNaN() }.toIntArray()
    val rows = kept.map { data.data[it] }
    val y = BooleanArray(rows.size) { rows[it][rhsIdx] in lo..hi }
    val nPos = y.count { it }
    val expPos = ds["n_pos"].str?.toInt()
    val expNeg = ds["n_neg"].str?.toInt()
    require(expPos == null || (nPos == expPos && rows.size - nPos == expNeg)) {
        "Dataset has $nPos positives / ${rows.size - nPos} negatives, the tree was built on $expPos / $expNeg. " +
                "Wrong dataset?"
    }

    val ruleRows = mutableListOf<Map<String, Any?>>()
    val nodeRows = mutableListOf<Map<String, Any?>>()
    val labels = IntArray(y.size) { if (y[it]) 1 else 0 }

    val scores = run.nodes.map { node ->
        val score = IntArray(rows.size)
        var exact = 0
        for (rule in node.rules) {
            val idx = rule.conditions.map { col[it.attribute] ?: error("Attribute ${it.attribute} not in $dataPath") }
            var tp = 0
            var fp = 0
            for ((r, row) in rows.withIndex()) {
                val covered = rule.conditions.indices.all { c ->
                    val v = row[idx[c]]
                    v >= rule.conditions[c].lo && v <= rule.conditions[c].hi
                }
                if (covered) {
                    score[r]++
                    if (y[r]) tp++ else fp++
                }
            }
            val ok = tp == rule.tp && fp == rule.fp
            if (ok) exact++
            ruleRows += linkedMapOf(
                "node_key" to node.key, "rule_id" to rule.id, "tp_stored" to rule.tp, "fp_stored" to rule.fp,
                "tp_recount" to tp, "fp_recount" to fp, "status" to if (ok) "exact" else "mismatch"
            )
        }
        val auc = AUC.compute(labels, DoubleArray(score.size) { score[it].toDouble() })
        nodeRows += linkedMapOf(
            "node_key" to node.key, "nID" to node.nId, "n_rules" to node.rules.size,
            "rules_exact" to exact, "rules_mismatch" to node.rules.size - exact,
            "auc_node_rules" to auc, "auc_stored" to node.auc,
            "max_score" to score.max(), "frac_covered" to score.count { it > 0 }.toDouble() / score.size
        )
        println("%-40s rules exact %d/%d, AUC %.4f (stored %s)".format(node.key, exact, node.rules.size, auc, node.auc))
        score
    }

    val result = NodeScores(run.nodes, kept, y, scores)
    val dir = File(out, "scores").apply { mkdirs() }
    writeCsv(File(dir, "scores.csv"), result.donorIds.indices.map { i ->
        linkedMapOf<String, Any?>("donor_id" to result.donorIds[i], "y" to if (y[i]) 1 else 0) +
                run.nodes.indices.associate { j -> run.nodes[j].key to scores[j][i] }
    })
    writeCsv(File(dir, "rule_check.csv"), ruleRows)
    writeCsv(File(dir, "node_check.csv"), nodeRows)
    val mismatched = ruleRows.count { it["status"] != "exact" }
    println("scores for ${run.nodes.size} nodes, ${y.size} donors; rules not reproduced: $mismatched -> $dir")
    return result
}

/** Minimal CSV writer: header from the first row's keys, NaN and null as empty cells. */
fun writeCsv(file: File, rows: List<Map<String, Any?>>) {
    file.parentFile?.mkdirs()
    fun cell(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN()) "" else v.toString()
        is String -> if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        else -> v.toString()
    }
    file.bufferedWriter().use { w ->
        val header = rows.firstOrNull()?.keys?.toList() ?: return@use
        w.appendLine(header.joinToString(",") { cell(it) })
        for (r in rows) w.appendLine(header.joinToString(",") { cell(r[it]) })
    }
}
