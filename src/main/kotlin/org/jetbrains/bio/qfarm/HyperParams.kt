package org.jetbrains.bio.qfarm

import kotlinx.serialization.Serializable

@Serializable
data class HyperParameters(
    // PARTICULAR EVOLUTIONS
    val popSizeAttrFirst: Int = 100,
    val maxGenAttrFirst: Int = 100,
    val popSizeAttrParent: Int = 500,
    val maxGenAttrParent: Int = 200,
    val popSizeRange: Int = 200,
    val maxGenRange: Int = 500,
    // UNIVERSAL EVOLUTION
    val probabilityMutation: Double = 0.75,
    val stdMutation: Double = 0.15,
    // RULE TREE BUILDING
    val maxDepth: Int = 2,
    val maxChildren: Int = 1,
    val maxFirstChildren: Int = 4,
    // THRESHOLD CONSTRAINTS
    val improvementThreshold: Double = 0.1,
    val minSupport: Int = 1,
    val maxSupport: Int = 1_000_000,
    // DATASET CHARACTERISTICS
    val dataPath: String? = null,
    val excludedColumns: List<String> = emptyList(),
    // RUN CHARACTERISTICS
    val runName: String = "test_run",
    // RIGHT ATTRIBUTE
    val rightAttribute: String? = null,
    val lowRight: Double = 0.0,  // as percentile
    val upRight: Double = 1.0,   // as percentile
)

// now mutable so CLI can override
var hp = HyperParameters()
