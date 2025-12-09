package org.jetbrains.bio.qfarm

data class HyperParameters(
    // PARTICULAR EVOLUTIONS
    val popSizeAttrFirst: Int = 100,
    val maxGenAttrFirst: Int = 100,
    val popSizeAttrParent: Int = 500,
    val maxGenAttrParent: Int = 200,
    val popSizeRange: Int = 200,
    val maxGenRange: Int = 500,
    // UNIVERSAL EVOLUTION (if needed)
    val populationSize: Int = 100,
    val maxGenerations: Int = 100,
    val probabilityMutation: Double = 1.0,
    val stdMutation: Double = 0.15,
    // RULE TREE BUILDING
    val maxDepth: Int = 2,
    val maxChildren: Int = 1,
    val maxFirstChildren: Int = 4,
    // THRESHOLD CONSTRAINTS
    val improvementThreshold: Double = 10.0,
    val minSupport: Int = 1,
    val maxSupport: Int = 1_000_000,
    // DATASET CHARACTERISTICS
    val dataPath: String? = null,
    val maxDatasetRows: Int = 23_739,
    val maxDatasetCols: Int = 10_000,
    val excludedColumns: List<String> = listOf("Sex"),
    // RIGHT ATTRIBUTE
    val rightAttribute: String? = null,
    val lowRight: Double = 0.9,  // as percentile
    val upRight: Double = 1.0   // as percentile
)

// now mutable so CLI can override
var hp = HyperParameters()
