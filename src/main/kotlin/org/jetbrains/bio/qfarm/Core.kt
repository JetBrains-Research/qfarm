package org.jetbrains.bio.qfarm

import io.jenetics.util.RandomRegistry
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evaluation.generateMedianFront
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.evolution.treeTraversal
import org.jetbrains.bio.qfarm.output.RHS
import org.jetbrains.bio.qfarm.output.RULE_TREE_ROOT
import org.jetbrains.bio.qfarm.output.RuleTreeJsonWriter
import org.jetbrains.bio.qfarm.output.toDOTFromTrie
import org.jetbrains.bio.qfarm.util.BLUE
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.computeBoundsFromSorted
import org.jetbrains.bio.qfarm.util.computeLabelsFast
import org.jetbrains.bio.qfarm.util.computeSortedColumns
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.bio.qfarm.util.loadNumericDataset
import org.jetbrains.bio.qfarm.util.printFirstRows
import org.jetbrains.bio.qfarm.util.removeRowsWithNaNRHS
import java.io.File
import kotlin.collections.MutableSet

const val PLOTS_DIR = "plots"

val rand = RandomRegistry.random()

// all these become lateinit / vars, initialized by initEnvironment()
lateinit var GLOBAL_ENV: EvolutionEnvironment
lateinit var datasetWithHeader: DatasetWithHeader
lateinit var columnNames: List<String>
lateinit var sortedColumns: List<DoubleArray>
lateinit var bounds: Array<DoubleArray>
lateinit var percentileProvider: SortedColumnsPercentileProvider
lateinit var init_cfg: RuleInitConfig
lateinit var rightGene: AttributeGene
var rightAttrIndex: Int = -1

var USED: MutableSet<Int> = mutableSetOf()
var TOPRULES: MutableList<List<Int>> = mutableListOf()
lateinit var RULE_JSON_WRITER: RuleTreeJsonWriter


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
    rightAttrIndex = columnNames.indexOf(rhsName)
    require(rightAttrIndex >= 0) { "Right-hand-side column '$rhsName' not found." }

    datasetWithHeader = removeRowsWithNaNRHS(datasetWithHeader, rightAttrIndex)

    sortedColumns = computeSortedColumns(datasetWithHeader.data)
    bounds = computeBoundsFromSorted(sortedColumns)
    percentileProvider = SortedColumnsPercentileProvider(sortedColumns)

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

    println("RANGE: $rhsLo to $rhsHi")

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
        pLeft = hp.lowRight,  // fillers, do not matter
        pRight = hp.upRight,  // fillers, do not matter
        cfg = init_cfg
    )

    datasetWithHeader.labels = computeLabelsFast(
        dataset = datasetWithHeader.data,
        rhsIndex = rightAttrIndex,
        lower = rhsLo,
        upper = rhsHi
    )

    val positives = datasetWithHeader.labels.sum()
    println("Positive labels: $positives / ${datasetWithHeader.labels.size}")

    GLOBAL_ENV = EvolutionEnvironment(
        datasetWithHeader = datasetWithHeader,
        columnNames = columnNames,
        sortedColumns = sortedColumns,
        bounds = bounds,
        percentileProvider = percentileProvider,
        rightAttrIndex = rightAttrIndex
    )

    generateMedianFront()

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
                " + ${BLUE}${columnNames[idx]}${RESET}"
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
