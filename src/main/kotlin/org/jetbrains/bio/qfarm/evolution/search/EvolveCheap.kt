package org.jetbrains.bio.qfarm.evolution.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.bio.qfarm.GLOBAL_ENV
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.evaluation.scan.CheapScan
import org.jetbrains.bio.qfarm.evaluation.scan.ParentContext
import org.jetbrains.bio.qfarm.evaluation.scan.ScanBox
import org.jetbrains.bio.qfarm.evolution.EvolutionContext
import org.jetbrains.bio.qfarm.evolution.ScanTotals
import org.jetbrains.bio.qfarm.evolution.cheapTopRange
import org.jetbrains.bio.qfarm.logger.ProgressBar
import org.jetbrains.bio.qfarm.util.readLHS
import org.jetbrains.bio.qfarm.rightAttrIndex
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.params.RED
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.cheapScanParams


/**
 * One candidate addition ranked by the cheap phase. With the box scan, [boxes] are the scan's
 * boxes over `prefix + attr` (seeds of the full evolution) and [modes] the box sets found;
 * with the cheap evolution only [auc] is set.
 */
data class CheapCandidate(
    val attr: Int,
    val auc: Double,
    val modes: Int = 1,
    val boxes: List<ScanBox> = emptyList(),
    val scanSeconds: Double? = null,
    val boxesEvaluated: Long? = null,
    /** Adjusted p-value of the second pass's knee box (see ScanResult.secondModeP); null without a second pass. */
    val secondModeP: Double? = null
)

fun evaluateCheapAdditions(
    prefix: List<Int>,
    used: Set<Int>,
    dataset: DatasetWithHeader = datasetWithHeader
): List<CheapCandidate> {

    val searchAttributes =
        (0 until dataset.header.size)
            .filter { it != rightAttrIndex }
            .filter { it !in prefix }
            .filter { it !in used }

    if (searchAttributes.isEmpty()) {
        println("$RED No attributes left to explore $RESET")
        return emptyList()
    }

    if (!cheapScanParams.enabled) {
        return runBlocking {
            searchAttributes.map { attr ->
                async(Dispatchers.Default) {

                    val front = cheapTopRange(prefix + attr)

                    val auc = AUC.compute(
                        dataset.labels,
                        front.scores,
                    )

                    CheapCandidate(attr, auc)
                }
            }.awaitAll()
        }
    }

    // Box scan: the parent front is read once per node and shared by all candidates.
    val env = GLOBAL_ENV
    val parent = ParentContext.of(EvolutionContext.frontStack.lastOrNull()?.front, env)
    val scanner = CheapScan(env, env.columnOrders)
    val label = "cheap " + (if (prefix.isEmpty()) "ROOT" else readLHS(prefix)) + " +"
    val bar = ProgressBar(label, searchAttributes.size)

    val result = runBlocking {
        searchAttributes.map { attr ->
            async(Dispatchers.Default) {
                val result = scanner.scan(prefix + attr, parent)
                ScanTotals.add(result.seconds)
                bar.step()
                CheapCandidate(
                    attr = attr,
                    auc = result.auc,
                    modes = result.modes,
                    boxes = result.boxes,
                    scanSeconds = result.seconds,
                    boxesEvaluated = result.boxesEvaluated,
                    secondModeP = result.secondModeP
                )
            }
        }.awaitAll()
    }
    bar.finish()
    return result
}
