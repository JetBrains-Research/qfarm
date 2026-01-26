package org.jetbrains.bio.qfarm

import io.jenetics.util.RandomRegistry
import java.io.File
import kotlin.collections.MutableSet

const val PLOTS_DIR = "plots"

val rand = RandomRegistry.random()

// all these become lateinit / vars, initialized by initEnvironment()
lateinit var datasetWithHeader: DatasetWithHeader
lateinit var columnNames: List<String>
lateinit var dataset: List<DoubleArray>
lateinit var sortedColumns: List<DoubleArray>
lateinit var bounds: Array<DoubleArray>
lateinit var percentileProvider: SortedColumnsPercentileProvider
lateinit var init_cfg: RuleInitConfig
lateinit var rightGene: AttributeGene
var rightAttrIndex: Int = -1

var USED: MutableSet<Int> = mutableSetOf()
var TOPRULES: MutableList<List<Int>> = mutableListOf()


fun initEnvironment(
    dataPath: String,
    rhsName: String,
    rhsRange: Pair<Double?, Double?>? = null,          // nullable endpoints
    rhsPercentiles: Pair<Double, Double>? = null       // 0.0–1.0
) {
    println("initEnvironment called with:")
    println("  dataset = $dataPath")
    println("  rhsName = $rhsName")
    println("  rhsRange = $rhsRange")
    println("  rhsPercentiles = $rhsPercentiles")

    datasetWithHeader = loadNumericDataset(
        filePath = dataPath,
        excludeColumns = hp.excludedColumns.toSet()
    )

    if (datasetWithHeader.header.size < 100) printFirstRows(datasetWithHeader)

    columnNames = datasetWithHeader.header
    dataset = datasetWithHeader.data
    sortedColumns = computeSortedColumns(dataset)
    bounds = computeBoundsFromSorted(sortedColumns)
    percentileProvider = SortedColumnsPercentileProvider(sortedColumns)

    rightAttrIndex = columnNames.indexOf(rhsName)
    require(rightAttrIndex >= 0) { "Right-hand-side column '$rhsName' not found." }

    val minC = bounds[rightAttrIndex][0]
    val maxC = bounds[rightAttrIndex][1]

    val (rhsLo, rhsHi) = when {
        rhsRange != null -> {
            val (loOpt, hiOpt) = rhsRange
            val lo = loOpt ?: minC   // null → MIN
            val hi = hiOpt ?: maxC   // null → MAX
            require(lo <= hi) { "--rhs-range lower must be <= upper" }
            lo to hi
        }
        rhsPercentiles != null -> {
            val (pLo, pHi) = rhsPercentiles
            val lo = percentileProvider.value(rightAttrIndex, pLo)
            val hi = percentileProvider.value(rightAttrIndex, pHi)
            require(lo <= hi) { "--rhs-range-percentile lower must be <= upper" }
            lo to hi
        }
        else -> {
            // fallback to hp.lowRight / hp.upRight as percentiles, if you want
            val lo = percentileProvider.value(rightAttrIndex, hp.lowRight)
            val hi = percentileProvider.value(rightAttrIndex, hp.upRight)
            lo to hi
        }
    }

    init_cfg = RuleInitConfig(
        rightAttrIndex = rightAttrIndex,
        bounds = bounds,
        percentile = percentileProvider
    )

    rightGene = AttributeGene(
        attributeIndex = rightAttrIndex,
        lowerBound = rhsLo,
        upperBound = rhsHi,
        min = minC,
        max = maxC,
        cfg = init_cfg
    )

    println("RANGE: $rhsLo to $rhsHi")

}

fun runSearch() {
    val start = System.nanoTime()

    RULE_JSON_WRITER = RuleTreeJsonWriter(
        File("plots/log_${hp.runName}.jsonl")
    )

    RULE_JSON_WRITER.writeMetadata(
        rhs = RHS(columnNames[rightAttrIndex], rightGene.lowerBound, rightGene.upperBound),
        hp
    )

    val emptyPrefix: MutableList<Int> = mutableListOf()
    treeTraversal(emptyPrefix)

    TOPRULES.forEach { rule ->
        println()
        rule.forEach { idx ->
            print(
                " + $BLUE${columnNames[idx]}$RESET"
            )
        }
    }

    val dot = toDOTFromTrie(RULE_TREE_ROOT, header = datasetWithHeader.header)
    val filename = "$PLOTS_DIR/tree_${hp.runName}"
    File("$filename.dot").writeText(dot)

    ProcessBuilder("dot", "-Tsvg", "$filename.dot", "-o", "$filename.svg")
        .redirectErrorStream(true)
        .start()
        .waitFor()

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("\nTOTAL RUNTIME: $elapsed s")

    RULE_JSON_WRITER.writeSummary(
        runtimeSeconds = elapsed,
        runName = hp.runName
    )

    RULE_JSON_WRITER.close()

}
