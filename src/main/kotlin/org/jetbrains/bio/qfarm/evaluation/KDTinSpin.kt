package org.jetbrains.bio.qfarm.evaluation

import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.tinspin.index.PointMap
import java.io.Closeable

data class TinSpinIndexedRow(
    val rowIndex: Int,
    val coordinates: DoubleArray,
    val isPositive: Boolean
)

data class TinSpinLocalHyperRectangle(
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

data class TinSpinRuleStats(
    val support: Int,
    val positiveCount: Int
) {
    val confidence: Double
        get() = if (support == 0) 0.0 else positiveCount.toDouble() / support
}

class TinSpinRangeEvaluationOracle(
    rows: List<TinSpinIndexedRow>,
    val attributes: List<Int>,
    indexKind: TinSpinIndexKind = TinSpinIndexKind.KD_TREE
) : Closeable {

    companion object {
        fun fromDataset(
            dataset: DatasetWithHeader,
            attributes: List<Int>,
            indexKind: TinSpinIndexKind = TinSpinIndexKind.KD_TREE
        ): TinSpinRangeEvaluationOracle {
            val rows = dataset.data.mapIndexed { rowIndex, row ->
                TinSpinIndexedRow(
                    rowIndex = rowIndex,
                    coordinates = row,
                    isPositive = dataset.labels[rowIndex] == 1
                )
            }

            return TinSpinRangeEvaluationOracle(
                rows = rows,
                attributes = attributes,
                indexKind = indexKind
            )
        }
    }

    val dims: Int = attributes.size

    private val attributeToLocalDim: Map<Int, Int> =
        attributes.withIndex().associate { it.value to it.index }

    private val index: PointMap<Boolean> =
        when (indexKind) {
            TinSpinIndexKind.KD_TREE ->
                PointMap.Factory.createKdTree(dims)

            TinSpinIndexKind.R_STAR_TREE ->
                PointMap.Factory.createRStarTree(dims)

            TinSpinIndexKind.PH_TREE ->
                PointMap.Factory.createPhTree(dims)
        }

    init {
        require(attributes.isNotEmpty()) {
            "attributes must not be empty"
        }

        for (row in rows) {
            val localCoordinates = DoubleArray(dims)

            for ((localDim, originalAttrIndex) in attributes.withIndex()) {
                val v = row.coordinates[originalAttrIndex]

                require(v.isFinite()) {
                    "Row ${row.rowIndex}, attribute $originalAttrIndex contains NaN or Infinity: $v"
                }

                localCoordinates[localDim] = v
            }

            index.insert(localCoordinates, row.isPositive)
        }
    }

    fun localDimensionOf(attributeIndex: Int): Int {
        return attributeToLocalDim[attributeIndex]
            ?: error("Attribute $attributeIndex is not part of this TinSpin oracle. Attributes=$attributes")
    }

    fun evaluate(rectangle: TinSpinLocalHyperRectangle): TinSpinRuleStats {
        require(rectangle.dims == dims) {
            "Rectangle has dimension ${rectangle.dims}, expected $dims"
        }

        var support = 0
        var positiveCount = 0

        val iterator = index.query(rectangle.min, rectangle.max)

        while (iterator.hasNext()) {
            val entry = iterator.next()

            support++

            if (entry.value()) {
                positiveCount++
            }
        }

        return TinSpinRuleStats(
            support = support,
            positiveCount = positiveCount
        )
    }

    override fun close() {
        index.clear()
    }
}

enum class TinSpinIndexKind {
    KD_TREE,
    R_STAR_TREE,
    PH_TREE
}
