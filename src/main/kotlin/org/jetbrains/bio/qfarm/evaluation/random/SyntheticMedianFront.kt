package org.jetbrains.bio.qfarm.evaluation.random

import org.jetbrains.bio.qfarm.evolution.ScoredFront
import org.jetbrains.bio.qfarm.util.DatasetWithHeader

object MedianFront {
    lateinit var scoredFront: ScoredFront
    lateinit var datasetWithHeader: DatasetWithHeader
    var auc: Double = Double.NaN
}

object RandomAucBaseline {
    lateinit var aucs: List<Double>

    fun requireReady() {
        require(::aucs.isInitialized && aucs.isNotEmpty()) {
            "Level1RandomAucBaseline was not initialized. " +
                    "Call generateLevel1RandomAucBaseline(...) before tree traversal or reconstruction."
        }
    }
}

fun empiricalAucPValueGreater(
    observedAuc: Double,
    randomAucs: List<Double>
): Double {
    require(randomAucs.isNotEmpty()) {
        "Random AUC baseline must not be empty."
    }

    val greaterOrEqual = randomAucs.count { it >= observedAuc }

    // TODO: yes, but if perfect, then 1/51 = 0.0196, so wtf...
    return (greaterOrEqual + 1.0) / (randomAucs.size + 1.0)
}

fun bonferroniCorrect(
    rawP: Double,
    nTests: Int
): Double {
    require(nTests > 0) { "nTests must be > 0" }
    return (rawP * nTests).coerceAtMost(1.0)
}
