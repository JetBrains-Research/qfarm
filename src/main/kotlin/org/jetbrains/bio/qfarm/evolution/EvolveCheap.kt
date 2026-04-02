package org.jetbrains.bio.qfarm.evolution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.bio.qfarm.datasetWithHeader
import org.jetbrains.bio.qfarm.rightAttrIndex
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.RED
import org.jetbrains.bio.qfarm.util.RESET


data class CheapCandidate(
    val attr: Int,
    val auc: Double,
//    val pval: Double  TODO: decide if you want to sort and filter by AUC or pval
)

fun evaluateCheapAdditions(
    prefix: List<Int>,
    dataset: DatasetWithHeader = datasetWithHeader
): List<CheapCandidate> {

    val searchAttributes =
        (0 until dataset.header.size)
            .filter { it != rightAttrIndex }
            .filter { it !in prefix }
            .filter { attr ->
                val newSet = (prefix + attr).toSet()
                newSet !in VISITED_SETS
            }

    if (searchAttributes.isEmpty()) {
        println("$RED No attributes left to explore $RESET")
        return emptyList()
    }

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
