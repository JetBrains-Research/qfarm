package org.jetbrains.bio.qfarm.evaluation

import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.DoublePoint
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
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
        private const val POSITIVE_FIELD = "positive"
        private const val POSITIVE_VALUE = "1"
        private const val NEGATIVE_VALUE = "0"

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

    private val attributeToLocalDim: Map<Int, Int> =
        attributes.withIndex().associate { it.value to it.index }

    private val directory: Directory = ByteBuffersDirectory()
    private val reader: DirectoryReader
    private val searcher: IndexSearcher

    init {
        require(attributes.isNotEmpty()) {
            "attributes must not be empty"
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
                    StringField(
                        POSITIVE_FIELD,
                        if (row.isPositive) POSITIVE_VALUE else NEGATIVE_VALUE,
                        Field.Store.NO
                    )
                )

                writer.addDocument(doc)
            }
        }

        reader = DirectoryReader.open(directory)
        searcher = IndexSearcher(reader)
    }

    fun localDimensionOf(attributeIndex: Int): Int {
        return attributeToLocalDim[attributeIndex]
            ?: error("Attribute $attributeIndex is not part of this oracle. Attributes=$attributes")
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

        val support = searcher.count(rangeQuery)

        val positiveQuery = BooleanQuery.Builder()
            .add(rangeQuery, BooleanClause.Occur.FILTER)
            .add(
                TermQuery(Term(POSITIVE_FIELD, POSITIVE_VALUE)),
                BooleanClause.Occur.FILTER
            )
            .build()

        val positiveCount = searcher.count(positiveQuery)

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
