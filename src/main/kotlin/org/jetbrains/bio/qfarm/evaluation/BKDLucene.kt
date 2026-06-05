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
import java.io.Closeable

data class IndexedRow(
    val rowIndex: Int,
    val coordinates: DoubleArray,
    val isPositive: Boolean
)

data class FeatureBounds(
    val min: DoubleArray,
    val max: DoubleArray
)

data class HyperRectangle(
    val indices: IntArray,
    val min: DoubleArray,
    val max: DoubleArray
) {
    init {
        require(min.size == max.size) {
            "min and max must have the same dimensionality"
        }

        for (i in min.indices) {
            require(min[i] <= max[i]) {
                "Invalid rectangle at dimension $i: min=${min[i]} > max=${max[i]}"
            }
            require(min[i].isFinite() && max[i].isFinite()) {
                "Rectangle contains NaN or Infinity at dimension $i"
            }
        }
    }
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
    val dims: Int
) : Closeable {

    companion object {
        private const val FEATURE_PREFIX = "feature_"
        private const val POSITIVE_FIELD = "positive"
        private const val POSITIVE_VALUE = "1"
        private const val NEGATIVE_VALUE = "0"

        private fun featureField(index: Int): String = "$FEATURE_PREFIX$index"
    }

    private val directory: Directory = ByteBuffersDirectory()
    private val reader: DirectoryReader
    private val searcher: IndexSearcher

    init {
        require(dims > 0) {
            "dims must be positive"
        }

        val analyzer = KeywordAnalyzer()
        val config = IndexWriterConfig(analyzer)

        IndexWriter(directory, config).use { writer ->
            for (row in rows) {
                require(row.coordinates.size == dims) {
                    "Row ${row.rowIndex} has dimension ${row.coordinates.size}, expected $dims"
                }

                require(row.coordinates.all { it.isFinite() }) {
                    "Row ${row.rowIndex} contains NaN or Infinity: ${row.coordinates.contentToString()}"
                }

                val doc = Document()

                for (i in 0 until dims) {
                    doc.add(
                        DoublePoint(
                            featureField(i),
                            row.coordinates[i]
                        )
                    )
                }

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

    fun evaluate(rectangle: HyperRectangle): RuleStats {
        val rangeBuilder = BooleanQuery.Builder()

        for (j in rectangle.indices.indices) {
            val attrIndex = rectangle.indices[j]

            rangeBuilder.add(
                DoublePoint.newRangeQuery(
                    featureField(attrIndex),
                    rectangle.min[j],
                    rectangle.max[j]
                ),
                BooleanClause.Occur.FILTER
            )
        }

        val rangeQuery = rangeBuilder.build()

        val support = searcher.count(rangeQuery)

        val positiveQuery = BooleanQuery.Builder()
            .add(rangeQuery, BooleanClause.Occur.FILTER)
            .add(
                TermQuery(Term(POSITIVE_FIELD, POSITIVE_VALUE)),
                BooleanClause.Occur.FILTER
            )
            .build()

        val positiveCount = searcher.count(positiveQuery)

        return RuleStats(support, positiveCount)
    }

    override fun close() {
        reader.close()
        directory.close()
    }
}
