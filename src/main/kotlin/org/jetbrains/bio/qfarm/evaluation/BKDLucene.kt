package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Genotype
import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.DoublePoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.SimpleCollector
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import java.io.Closeable

data class IndexedRow(
    val rowIndex: Int,
    val coordinates: DoubleArray,
    val isPositive: Boolean
)

data class LocalHyperRectangle(
    val min: DoubleArray,
    val max: DoubleArray
) {
    init {
        require(min.size == max.size) {
            "min and max must have the same dimensionality"
        }

        for (i in min.indices) {
            require(min[i] <= max[i]) {
                "Invalid rectangle at local dimension $i: min=${min[i]} > max=${max[i]}"
            }
            require(min[i].isFinite() && max[i].isFinite()) {
                "Rectangle contains NaN or Infinity at local dimension $i"
            }
        }
    }

    val dims: Int
        get() = min.size
}

data class RuleStats(
    val support: Int,
    val positiveCount: Int
) {
    val confidence: Double
        get() = if (support == 0) 0.0 else positiveCount.toDouble() / support
}

/**
 * Lucene-based range evaluation oracle.
 *
 * It indexes each row as a Lucene document:
 *   - coordinates are stored as one multidimensional DoublePoint field
 *   - label is stored as a StringField: "1" for positive, "0" for negative
 *
 * Query:
 *   - support = count(range query)
 *   - positiveCount = count(range query AND positive label)
 */
class LuceneRangeEvaluationOracle(
    rows: List<IndexedRow>,
    val attributes: List<Int>
) : Closeable {

    companion object {
        private const val FEATURES_FIELD = "features"
        private const val POSITIVE_DV_FIELD = "positive_dv"

        fun fromDataset(
            dataset: DatasetWithHeader,
            attributes: List<Int>
        ): LuceneRangeEvaluationOracle {
            val rows = dataset.data.mapIndexed { rowIndex, row ->
                IndexedRow(
                    rowIndex = rowIndex,
                    coordinates = row,
                    isPositive = dataset.labels[rowIndex] == 1
                )
            }

            return LuceneRangeEvaluationOracle(
                rows = rows,
                attributes = attributes
            )
        }
    }

    val dims: Int = attributes.size

    private val attributeToLocalDim: IntArray = run {
        val maxAttr = attributes.maxOrNull()
            ?: error("attributes must not be empty")

        val map = IntArray(maxAttr + 1) { -1 }

        for ((localDim, originalAttrIndex) in attributes.withIndex()) {
            map[originalAttrIndex] = localDim
        }

        map
    }

    private val directory: Directory = ByteBuffersDirectory()
    private val reader: DirectoryReader
    private val searcher: IndexSearcher

    init {
        require(attributes.isNotEmpty()) {
            "attributes must not be empty"
        }

        require(attributes.distinct().size == attributes.size) {
            "attributes must not contain duplicates: $attributes"
        }

        require(attributes.size <= 8) {
            "Lucene multidimensional DoublePoint supports at most 8 dimensions; got ${attributes.size}"
        }

        val analyzer = KeywordAnalyzer()
        val config = IndexWriterConfig(analyzer)

        IndexWriter(directory, config).use { writer ->
            for (row in rows) {
                val localCoordinates = DoubleArray(dims)

                for ((localDim, originalAttrIndex) in attributes.withIndex()) {
                    val v = row.coordinates[originalAttrIndex]

                    require(v.isFinite()) {
                        "Row ${row.rowIndex}, attribute $originalAttrIndex contains NaN or Infinity: $v"
                    }

                    localCoordinates[localDim] = v
                }

                val doc = Document()

                doc.add(DoublePoint(FEATURES_FIELD, *localCoordinates))

                doc.add(
                    NumericDocValuesField(
                        POSITIVE_DV_FIELD,
                        if (row.isPositive) 1L else 0L
                    )
                )

                writer.addDocument(doc)
            }
        }

        reader = DirectoryReader.open(directory)
        searcher = IndexSearcher(reader)
    }

    fun localDimensionOf(attributeIndex: Int): Int {
        if (attributeIndex !in attributeToLocalDim.indices) {
            error("Attribute $attributeIndex is not part of this Lucene oracle. Attributes=$attributes")
        }

        val localDim = attributeToLocalDim[attributeIndex]

        if (localDim < 0) {
            error("Attribute $attributeIndex is not part of this Lucene oracle. Attributes=$attributes")
        }

        return localDim
    }

    fun supportOf(
        genotype: Genotype<AttributeGene>,
        globalBounds: Array<DoubleArray>
    ): Int {
        val lhs = genotype[0] as RuleSideChromosome

        val min = DoubleArray(dims)
        val max = DoubleArray(dims)

        for ((localDim, originalAttrIndex) in attributes.withIndex()) {
            min[localDim] = globalBounds[originalAttrIndex][0]
            max[localDim] = globalBounds[originalAttrIndex][1]
        }

        var hasActiveGene = false

        for (i in 0 until lhs.length()) {
            val g = lhs[i]

            if (!g.isDefault) {
                hasActiveGene = true

                val localDim = localDimensionOf(g.attributeIndex)

                min[localDim] = g.lowerBound
                max[localDim] = g.upperBound
            }
        }

        if (!hasActiveGene) {
            return 0
        }

        val rangeQuery = DoublePoint.newRangeQuery(
            FEATURES_FIELD,
            min,
            max
        )

        return searcher.count(rangeQuery)
    }

    fun evaluate(rectangle: LocalHyperRectangle): RuleStats {
        require(rectangle.dims == dims) {
            "Rectangle has dimension ${rectangle.dims}, expected $dims"
        }

        val rangeQuery = DoublePoint.newRangeQuery(
            FEATURES_FIELD,
            rectangle.min,
            rectangle.max
        )

        var support = 0
        var positiveCount = 0

        searcher.search(
            rangeQuery,
            object : SimpleCollector() {

                private lateinit var positiveValues: NumericDocValues

                override fun doSetNextReader(context: LeafReaderContext) {
                    positiveValues = DocValues.getNumeric(
                        context.reader(),
                        POSITIVE_DV_FIELD
                    )
                }

                override fun collect(doc: Int) {
                    support++

                    if (
                        positiveValues.advanceExact(doc) &&
                        positiveValues.longValue() == 1L
                    ) {
                        positiveCount++
                    }
                }

                override fun scoreMode(): ScoreMode {
                    return ScoreMode.COMPLETE_NO_SCORES
                }
            }
        )

        return RuleStats(
            support = support,
            positiveCount = positiveCount
        )
    }

    override fun close() {
        reader.close()
        directory.close()
    }
}
