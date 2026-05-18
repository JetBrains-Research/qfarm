package org.jetbrains.bio.qfarm.util

import kotlinx.serialization.Serializable

@Serializable
data class HyperParameters(
    // PARTICULAR EVOLUTIONS
    val popSizeCheap: Int = 100,
    val maxGenCheap: Int = 100,
    val popSizeFull: Int = 500,
    val maxGenFull: Int = 500,
    // UNIVERSAL EVOLUTION
    val probabilityMutation: Double = 0.75,
    val stdMutation: Double = 0.02,
    // RULE TREE BUILDING
    val maxDepth: Int = 2,
    val maxChildren: Int = 1,
    val maxFirstChildren: Int = 4,
    // THRESHOLD CONSTRAINTS
    val minSupport: Int = 1,
    val maxSupport: Int = 1_000_000,
    // DATASET CHARACTERISTICS
    val dataPath: String? = null,
    val excludedColumns: List<String> = emptyList(),
    // RUN CHARACTERISTICS
    val runName: String = "test_run",
    // RIGHT ATTRIBUTE
    val rightAttribute: String? = null,
    var lowRight: Double = 0.0,  // as percentile
    var upRight: Double = 100.0,   // as percentile
    // ADDED TEMPORARY
    val alphaThreshold: Double = 0.05,
    val maxWidth: Double = 0.8,
)

// now mutable so CLI can override
var hp = HyperParameters()
