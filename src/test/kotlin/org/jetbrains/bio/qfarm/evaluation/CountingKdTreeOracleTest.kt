package org.jetbrains.bio.qfarm.evaluation

import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.computeBoundsFromSorted
import org.jetbrains.bio.qfarm.util.computeLabelsFast
import org.jetbrains.bio.qfarm.util.computeSortedColumns
import org.jetbrains.bio.qfarm.util.loadNumericDataset
import org.jetbrains.bio.qfarm.util.removeRowsWithNaNRHS
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CountingKdTreeOracleTest {

    private val verbose: Boolean =
        System.getProperty("qfarm.test.verbose") == "true"

    @Test
    fun `countRange and supportRange match linear scan on simple fixed query`() {
        val dataset = toyDataset()
        val attributes = listOf(1, 2)
        val bounds = computeTestBounds(dataset)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 2)
            .use { oracle ->
                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = doubleArrayOf(2.0, 10.0),
                    queryMax = doubleArrayOf(5.0, 30.0),
                    queryId = "toy-fixed"
                )
            }
    }

    @Test
    fun `countRange and supportRange match linear scan when query covers full selected bounds`() {
        val dataset = toyDataset()
        val attributes = listOf(1, 2, 3)
        val bounds = computeTestBounds(dataset)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 2)
            .use { oracle ->
                val queryMin = DoubleArray(attributes.size) { localDim ->
                    bounds[attributes[localDim]][0]
                }

                val queryMax = DoubleArray(attributes.size) { localDim ->
                    bounds[attributes[localDim]][1]
                }

                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = queryMin,
                    queryMax = queryMax,
                    queryId = "toy-full-bounds"
                )
            }
    }

    @Test
    fun `countRange and supportRange match linear scan for empty result query`() {
        val dataset = toyDataset()
        val attributes = listOf(1, 2)
        val bounds = computeTestBounds(dataset)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 2)
            .use { oracle ->
                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = doubleArrayOf(999.0, 999.0),
                    queryMax = doubleArrayOf(1000.0, 1000.0),
                    queryId = "toy-empty"
                )
            }
    }

    @Test
    fun `countRange and supportRange match linear scan for single-point boundary query`() {
        val dataset = toyDataset()
        val attributes = listOf(1, 2)
        val bounds = computeTestBounds(dataset)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 1)
            .use { oracle ->
                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = doubleArrayOf(3.0, 30.0),
                    queryMax = doubleArrayOf(3.0, 30.0),
                    queryId = "toy-single-point"
                )
            }
    }

    @Test
    fun `countRange and supportRange match linear scan for one-dimensional tree`() {
        val dataset = toyDataset()
        val attributes = listOf(1)
        val bounds = computeTestBounds(dataset)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 2)
            .use { oracle ->
                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = doubleArrayOf(2.0),
                    queryMax = doubleArrayOf(4.0),
                    queryId = "toy-1d"
                )
            }
    }

    @Test
    fun `countRange and supportRange match linear scan for many random queries`() {
        val dataset = randomDataset(rows = 500, cols = 6, seed = 123)
        val attributes = listOf(1, 3, 5)
        val bounds = computeTestBounds(dataset)
        val rnd = Random(42)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 16)
            .use { oracle ->
                repeat(1_000) { queryId ->
                    val (queryMin, queryMax) = randomQuery(attributes, bounds, rnd)

                    assertKdEqualsLinear(
                        dataset = dataset,
                        attributes = attributes,
                        oracle = oracle,
                        queryMin = queryMin,
                        queryMax = queryMax,
                        queryId = "random-500-$queryId"
                    )
                }
            }
    }

    @Test
    fun `countRange and supportRange match linear scan for random dimensions and leaf sizes`() {
        val dataset = randomDataset(rows = 1_000, cols = 8, seed = 999)
        val bounds = computeTestBounds(dataset)
        val rnd = Random(7)

        val attributeSets = listOf(
            listOf(0),
            listOf(2),
            listOf(1, 4),
            listOf(0, 3, 6),
            listOf(2, 4, 5, 7),
            listOf(7, 2, 5)
        )

        val leafSizes = listOf(1, 4, 16, 64, 2_000)

        for (attributes in attributeSets) {
            for (leafSize in leafSizes) {
                CountingKdTreeOracle
                    .fromDataset(dataset, attributes, bounds, leafSize)
                    .use { oracle ->
                        repeat(250) { queryId ->
                            val (queryMin, queryMax) = randomQuery(attributes, bounds, rnd)

                            assertKdEqualsLinear(
                                dataset = dataset,
                                attributes = attributes,
                                oracle = oracle,
                                queryMin = queryMin,
                                queryMax = queryMax,
                                queryId = "random-dims-attrs=$attributes-leaf=$leafSize-query=$queryId"
                            )
                        }
                    }
            }
        }
    }

    @Test
    fun `countRange and supportRange match linear scan with duplicate coordinates`() {
        val dataset = DatasetWithHeader(
            header = listOf("rhs", "a", "b"),
            data = listOf(
                doubleArrayOf(1.0, 5.0, 5.0),
                doubleArrayOf(0.0, 5.0, 5.0),
                doubleArrayOf(1.0, 5.0, 5.0),
                doubleArrayOf(0.0, 5.0, 5.0),
                doubleArrayOf(1.0, 10.0, 10.0)
            ),
            labels = intArrayOf(1, 0, 1, 0, 1)
        )

        val attributes = listOf(1, 2)
        val bounds = computeTestBounds(dataset)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 1)
            .use { oracle ->
                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = doubleArrayOf(5.0, 5.0),
                    queryMax = doubleArrayOf(5.0, 5.0),
                    queryId = "duplicates-exact"
                )

                assertKdEqualsLinear(
                    dataset = dataset,
                    attributes = attributes,
                    oracle = oracle,
                    queryMin = doubleArrayOf(0.0, 0.0),
                    queryMax = doubleArrayOf(100.0, 100.0),
                    queryId = "duplicates-full"
                )
            }
    }

    @Test
    fun `real friedman dataset random queries match linear scan`() {
        val datasetPath = "data/friedman1.csv"

        if (!File(datasetPath).exists()) {
            println("Skipping Friedman real dataset test because file does not exist: $datasetPath")
            return
        }

        val prepared = prepareDatasetWithLabels(
            filePath = datasetPath,
            rhsName = "y",
            rhsPercentiles = 0.8 to 1.0
        )

        val dataset = prepared.dataset
        val bounds = prepared.bounds
        val attributes = pickExistingAttributes(dataset, listOf(0, 1, 2))
        val rnd = Random(1234)

        printlnRealDatasetHeader("friedman", datasetPath, prepared, attributes)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 32)
            .use { oracle ->
                repeat(1_000) { queryId ->
                    val (queryMin, queryMax) = randomQuery(attributes, bounds, rnd)

                    assertKdEqualsLinear(
                        dataset = dataset,
                        attributes = attributes,
                        oracle = oracle,
                        queryMin = queryMin,
                        queryMax = queryMax,
                        queryId = "friedman-$queryId"
                    )
                }
            }
    }

    @Test
    fun `real nhanes dataset random queries match linear scan if file exists`() {
        val datasetPath = "data/nhanes_5yr_train.csv"

        if (!File(datasetPath).exists()) {
            println("Skipping real NHANES test because file does not exist: $datasetPath")
            return
        }

        val prepared = prepareDatasetWithLabels(
            filePath = datasetPath,
            rhsName = "death_in_next_5yrs",
            rhsRange = 1.0 to 1.0
        )

        val dataset = prepared.dataset
        val bounds = prepared.bounds
        val attributes = pickExistingAttributes(dataset, listOf(1, 3, 6, 10))
        val rnd = Random(5678)

        printlnRealDatasetHeader("nhanes", datasetPath, prepared, attributes)

        CountingKdTreeOracle
            .fromDataset(dataset, attributes, bounds, leafSize = 32)
            .use { oracle ->
                repeat(2_000) { queryId ->
                    val (queryMin, queryMax) = randomQuery(attributes, bounds, rnd)

                    assertKdEqualsLinear(
                        dataset = dataset,
                        attributes = attributes,
                        oracle = oracle,
                        queryMin = queryMin,
                        queryMax = queryMax,
                        queryId = "nhanes-$queryId"
                    )
                }
            }
    }

    private fun assertKdEqualsLinear(
        dataset: DatasetWithHeader,
        attributes: List<Int>,
        oracle: CountingKdTreeOracle,
        queryMin: DoubleArray,
        queryMax: DoubleArray,
        queryId: String
    ) {
        val kdStart = System.nanoTime()
        val kd = oracle.countRange(queryMin, queryMax)
        val kdNs = System.nanoTime() - kdStart

        val supportStart = System.nanoTime()
        val kdSupportOnly = oracle.supportRange(queryMin, queryMax)
        val supportNs = System.nanoTime() - supportStart

        val linearStart = System.nanoTime()
        val linear = linearCount(dataset, attributes, queryMin, queryMax)
        val linearNs = System.nanoTime() - linearStart

        if (verbose) {
            println(
                """
                ----------------------------------------
                QUERY ID   : $queryId
                ATTRIBUTES : $attributes
                QUERY MIN  : ${queryMin.contentToString()}
                QUERY MAX  : ${queryMax.contentToString()}

                LINEAR:
                  support         = ${linear.support}
                  positiveSupport = ${linear.positiveSupport}
                  confidence      = ${linear.confidence}
                  time            = ${linearNs / 1_000.0} us

                KD countRange:
                  support         = ${kd.support}
                  positiveSupport = ${kd.positiveSupport}
                  confidence      = ${kd.confidence}
                  time            = ${kdNs / 1_000.0} us

                KD supportRange:
                  support         = $kdSupportOnly
                  time            = ${supportNs / 1_000.0} us
                ----------------------------------------
                """.trimIndent()
            )
        }

        assertEquals(
            expected = linear.support,
            actual = kd.support,
            message = "countRange support mismatch for queryId=$queryId"
        )

        assertEquals(
            expected = linear.positiveSupport,
            actual = kd.positiveSupport,
            message = "countRange positiveSupport mismatch for queryId=$queryId"
        )

        assertEquals(
            expected = linear.confidence,
            actual = kd.confidence,
            message = "countRange confidence mismatch for queryId=$queryId"
        )

        assertEquals(
            expected = linear.support,
            actual = kdSupportOnly,
            message = "supportRange mismatch for queryId=$queryId"
        )
    }

    private fun linearCount(
        dataset: DatasetWithHeader,
        attributes: List<Int>,
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): KdCountResult {
        var support = 0
        var positiveSupport = 0

        for (r in dataset.data.indices) {
            val row = dataset.data[r]
            var ok = true

            for (localDim in attributes.indices) {
                val originalAttr = attributes[localDim]
                val v = row[originalAttr]

                if (v.isNaN() || v < queryMin[localDim] || v > queryMax[localDim]) {
                    ok = false
                    break
                }
            }

            if (ok) {
                support++

                if (dataset.labels[r] == 1) {
                    positiveSupport++
                }
            }
        }

        return KdCountResult(support, positiveSupport)
    }

    private fun prepareDatasetWithLabels(
        filePath: String,
        rhsName: String,
        rhsRange: Pair<Double?, Double?>? = null,
        rhsPercentiles: Pair<Double, Double>? = null
    ): PreparedDataset {
        var dataset = loadNumericDataset(
            filePath = filePath,
            excludeColumns = emptySet()
        )

        val rhsIndex = dataset.header.indexOf(rhsName)
        require(rhsIndex >= 0) {
            "RHS column '$rhsName' not found. Available columns=${dataset.header}"
        }

        dataset = removeRowsWithNaNRHS(dataset, rhsIndex)

        val sortedColumns = computeSortedColumns(dataset.data)
        val bounds = computeBoundsFromSorted(sortedColumns)
        val percentileProvider = SortedColumnsPercentileProvider(sortedColumns)

        val minC = bounds[rhsIndex][0]
        val maxC = bounds[rhsIndex][1]

        val (rhsLo, rhsHi) = when {
            rhsRange != null -> {
                val lo = rhsRange.first ?: minC
                val hi = rhsRange.second ?: maxC
                require(lo <= hi)
                lo to hi
            }

            rhsPercentiles != null -> {
                val lo = percentileProvider.value(rhsIndex, rhsPercentiles.first)
                val hi = percentileProvider.value(rhsIndex, rhsPercentiles.second)
                require(lo <= hi)
                lo to hi
            }

            else -> minC to maxC
        }

        dataset.labels = computeLabelsFast(
            dataset = dataset.data,
            rhsIndex = rhsIndex,
            lower = rhsLo,
            upper = rhsHi
        )

        require(dataset.labels.size == dataset.data.size)

        return PreparedDataset(dataset, bounds, rhsIndex, rhsLo, rhsHi)
    }

    private data class PreparedDataset(
        val dataset: DatasetWithHeader,
        val bounds: Array<DoubleArray>,
        val rhsIndex: Int,
        val rhsLo: Double,
        val rhsHi: Double
    )

    private fun randomQuery(
        attributes: List<Int>,
        bounds: Array<DoubleArray>,
        rnd: Random
    ): Pair<DoubleArray, DoubleArray> {
        val queryMin = DoubleArray(attributes.size)
        val queryMax = DoubleArray(attributes.size)

        for (localDim in attributes.indices) {
            val originalAttr = attributes[localDim]
            val loBound = bounds[originalAttr][0]
            val hiBound = bounds[originalAttr][1]

            val a = rnd.nextDouble(loBound, hiBound)
            val b = rnd.nextDouble(loBound, hiBound)

            queryMin[localDim] = minOf(a, b)
            queryMax[localDim] = maxOf(a, b)
        }

        return queryMin to queryMax
    }

    private fun toyDataset(): DatasetWithHeader =
        DatasetWithHeader(
            header = listOf("rhs", "a", "b", "c"),
            data = listOf(
                doubleArrayOf(1.0, 1.0, 10.0, 100.0),
                doubleArrayOf(0.0, 2.0, 20.0, 200.0),
                doubleArrayOf(1.0, 3.0, 30.0, 300.0),
                doubleArrayOf(0.0, 4.0, 40.0, 400.0),
                doubleArrayOf(1.0, 5.0, 50.0, 500.0)
            ),
            labels = intArrayOf(1, 0, 1, 0, 1)
        )

    private fun randomDataset(
        rows: Int,
        cols: Int,
        seed: Int
    ): DatasetWithHeader {
        val rnd = Random(seed)

        return DatasetWithHeader(
            header = List(cols) { "c$it" },
            data = List(rows) {
                DoubleArray(cols) {
                    rnd.nextDouble(-100.0, 100.0)
                }
            },
            labels = IntArray(rows) {
                if (rnd.nextBoolean()) 1 else 0
            }
        )
    }

    private fun computeTestBounds(dataset: DatasetWithHeader): Array<DoubleArray> {
        val cols = dataset.data.first().size

        return Array(cols) { c ->
            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY

            for (row in dataset.data) {
                val v = row[c]
                if (v < min) min = v
                if (v > max) max = v
            }

            doubleArrayOf(min, max)
        }
    }

    private fun pickExistingAttributes(
        dataset: DatasetWithHeader,
        preferred: List<Int>
    ): List<Int> {
        val nCols = dataset.data.first().size

        return preferred
            .filter { it in 0 until nCols }
            .ifEmpty {
                (0 until minOf(3, nCols)).toList()
            }
    }

    private fun printlnRealDatasetHeader(
        testName: String,
        datasetPath: String,
        prepared: PreparedDataset,
        attributes: List<Int>
    ) {
        val dataset = prepared.dataset

        println(
            """
            ========================================
            REAL DATASET TEST: $testName
            path       = $datasetPath
            rows       = ${dataset.data.size}
            cols       = ${dataset.data.firstOrNull()?.size ?: 0}
            attributes = $attributes
            names      = ${attributes.map { dataset.header[it] }}
            rhsIndex   = ${prepared.rhsIndex}
            rhsName    = ${dataset.header[prepared.rhsIndex]}
            rhsRange   = ${prepared.rhsLo} .. ${prepared.rhsHi}
            positives  = ${dataset.labels.sum()} / ${dataset.labels.size}
            verbose    = $verbose
            ========================================
            """.trimIndent()
        )
    }
}
