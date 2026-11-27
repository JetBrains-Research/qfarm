package org.jetbrains.bio.qfarm.output.export

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import kotlinx.serialization.json.*
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.discreteInfo
import org.jetbrains.bio.qfarm.evaluation.fronts.toPFSeries
import org.jetbrains.bio.qfarm.evaluation.scan.ScanBox
import org.jetbrains.bio.qfarm.evolution.EvolutionStats
import org.jetbrains.bio.qfarm.evolution.EvolutionTotals
import org.jetbrains.bio.qfarm.evolution.ScanTotals
import org.jetbrains.bio.qfarm.evolution.search.CheapCandidate
import org.jetbrains.bio.qfarm.output.logs.RuleStep
import org.jetbrains.bio.qfarm.output.fronts.buildExportRows
import org.jetbrains.bio.qfarm.output.tree.RuleTreeNode
import org.jetbrains.bio.qfarm.params.cheapScanParams
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.params.stopParams
import org.jetbrains.bio.qfarm.rightAttrIndex
import org.jetbrains.bio.qfarm.rightGene
import org.jetbrains.bio.qfarm.statistics.delong.DeLongResult
import org.jetbrains.bio.qfarm.visualization.attrsToFileName
import java.io.File
import java.security.MessageDigest
import java.time.Instant

const val TREE_JSON_SCHEMA_VERSION = 6

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
        put("stop_criterion", Json { encodeDefaults = true }.encodeToJsonElement(stopParams))
        put("cheap_scan", Json { encodeDefaults = true }.encodeToJsonElement(cheapScanParams))
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
            put(
                "evolution",
                "node.evolution: the FULL evolution that produced the node's front. generations < max_generations " +
                        "only when the stop criterion ended it (stop_mode STOP); stop_generation is the first generation " +
                        "the criterion fired (null if never or OFF). seconds: wall-clock time of the evolution (evolutions " +
                        "run in parallel); evolve_seconds: Jenetics engine time only, without stop-criterion logging. " +
                        "summary.evolutions: the same summed per phase over all evolutions of the run, retained or not."
            )
            put(
                "rules_at_stop",
                "node.rules_at_stop: LOG_ONLY only, the Pareto front at evolution.stop_generation, i.e. the rules an " +
                        "early stop would report, same format as node.rules (node.rules is then the front after " +
                        "max_generations). null in STOP (node.rules already is the front at the stop), in OFF, and when " +
                        "the criterion never fired."
            )
            put(
                "stop_criterion",
                "metadata.stop_criterion: settings of the criterion named in stop_criterion.criterion. crowding-stability " +
                        "(Rudenko and Schoenauer 2004): d(l) = largest finite crowding distance among the distinct " +
                        "non-dominated (support, confidence) points of generation l; fires when the standard deviation of " +
                        "d over the last windowLength generations is at most the limit, from minGenerations on. " +
                        "limit = max(maxStdDev, limitPerIndividual / population), per evolution with its own population."
            )
            put(
                "cheap_scan",
                "metadata.cheap_scan: settings of the cheap phase's box scan (cheap_scan.enabled false = cheap evolution " +
                        "as before, summary.evolutions.CHEAP). With the scan, every candidate prefix+attr is ranked by " +
                        "node.cheap_auc: AUC of the per-row count of scan boxes (plus the parent rules as parameters.rocComparison " +
                        "says). Boxes: depth 1 = best window per window size along the attribute (the random-AUC baseline's " +
                        "statistic on the real order); depth >= 2 = all boxes of a percentile grid; thinned to one per window-size " +
                        "bin and reduced to the (support, confidence) Pareto set. node.cheap_boxes: the boxes, same conditions " +
                        "format as rules, support/positives on all rows, pass = box set: 1 = first set (whole Pareto set); " +
                        "2 = knee boxes (up to the largest WRAcc) of the scan over the rows not covered by set 1's knee boxes, " +
                        "kept only if significant (hypergeometric, Bonferroni over boxes searched; node.cheap_mode2_p is that " +
                        "adjusted p, null without a second pass), above the base rate on all rows, and separated from set 1 by a " +
                        "dip below the base rate. node.cheap_modes = number of sets. All boxes seed the full evolution. " +
                        "node.cheap_seconds: wall time of the scan while other scans run in parallel (JIT warm-up inflates the " +
                        "first ones). summary.cheap_scans: count and seconds of all scans of the run."
            )
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
            put("evolutions", buildJsonObject {
                for ((phase, t) in EvolutionTotals.snapshot()) put(phase.name, buildJsonObject {
                    put("count", t.evolutions)
                    put("generations", t.generations)
                    put("seconds", num(t.seconds))
                    put("evolve_seconds", num(t.evolveSeconds))
                })
            })
            put("cheap_scans", ScanTotals.snapshot().let { s ->
                buildJsonObject {
                    put("count", s.scans)
                    put("seconds", num(s.seconds))
                }
            })
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

    val cheap = meta["cheap"] as? CheapCandidate
    put("cheap_auc", num(cheap?.auc))
    put("cheap_modes", cheap?.modes)
    put("cheap_mode2_p", num(cheap?.secondModeP))
    put("cheap_seconds", num(cheap?.scanSeconds))
    put("cheap_boxes", cheap?.let { cheapBoxesJson(it.boxes) } ?: JsonNull)

    val stopFront = step.scoredFront.evolution?.stopFront

    put("n_rules", front.size())
    put("n_rules_at_stop", stopFront?.size())
    put("evolution", step.scoredFront.evolution?.let(::evolutionJson) ?: JsonNull)
    put("rules", rulesJson(front))
    put("rules_at_stop", stopFront?.let(::rulesJson) ?: JsonNull)
}

/** The rules of a Pareto front, with their confusion counts on the dataset. */
private fun rulesJson(front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>): JsonArray {
    if (front.isEmpty) return JsonArray(emptyList())

    val metrics = toPFSeries(front, "Child", datasetWithHeader).metrics!!
    return buildJsonArray {
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
    }
}

/** The boxes of the cheap scan, in the conditions format of [rulesJson]. */
private fun cheapBoxesJson(boxes: List<ScanBox>): JsonArray = buildJsonArray {
    for ((i, b) in boxes.withIndex()) add(buildJsonObject {
        put("box_id", i)
        put("pass", b.pass)
        put("support", b.support)
        put("positives", b.positives)
        put("confidence", num(b.confidence))
        put("conditions", buildJsonArray {
            for (j in b.attributes.indices) add(buildJsonObject {
                val attr = b.attributes[j]
                put("attribute", columnNames[attr])
                put("kind", if (discreteInfo.isDiscrete[attr]) "discrete" else "numeric")
                put("lo", num(b.lo[j]))
                put("hi", num(b.hi[j]))
                put("p_lo", num(b.pLo[j]))
                put("p_hi", num(b.pHi[j]))
            })
        })
    })
}

private fun evolutionJson(e: EvolutionStats): JsonObject = buildJsonObject {
    put("phase", e.phase.name)
    put("population_size", e.populationSize)
    put("max_generations", e.maxGenerations)
    put("generations", e.generations)
    put("seconds", num(e.seconds))
    put("evolve_seconds", num(e.evolveSeconds))
    put("stop_mode", e.stopMode.name)
    put("stop_generation", e.stopGeneration)
}

fun sha256(file: File): String? = runCatching {
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
