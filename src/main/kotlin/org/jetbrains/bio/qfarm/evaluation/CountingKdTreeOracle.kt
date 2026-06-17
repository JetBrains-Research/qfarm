package org.jetbrains.bio.qfarm.evaluation

import io.jenetics.Genotype
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import java.io.Closeable

data class KdCountResult(
    val support: Int,
    val positiveSupport: Int
) {
    val confidence: Double
        get() = if (support == 0) 0.0 else positiveSupport.toDouble() / support
}

class CountingKdTreeOracle(
    private val points: Array<DoubleArray>,
    private val labels: IntArray,
    val attributes: List<Int>,
    private val globalBounds: Array<DoubleArray>,
    private val leafSize: Int = 32
) : Closeable {

    companion object {
        fun fromDataset(
            dataset: DatasetWithHeader,
            attributes: List<Int>,
            globalBounds: Array<DoubleArray>,
            leafSize: Int = 32
        ): CountingKdTreeOracle {

            require(attributes.isNotEmpty()) {
                "attributes must not be empty"
            }

            require(attributes.distinct().size == attributes.size) {
                "attributes must not contain duplicates: $attributes"
            }

            require(dataset.labels.size == dataset.data.size) {
                "dataset.labels.size=${dataset.labels.size} must equal dataset.data.size=${dataset.data.size}. " +
                        "Compute labels before building CountingKdTreeOracle."
            }

            require(leafSize >= 1) {
                "leafSize must be >= 1, got $leafSize"
            }

            val dims = attributes.size

            val points = Array(dataset.data.size) { rowIndex ->
                val row = dataset.data[rowIndex]
                DoubleArray(dims) { localDim ->
                    val originalAttrIndex = attributes[localDim]
                    val v = row[originalAttrIndex]

                    require(v.isFinite()) {
                        "Row $rowIndex, attribute $originalAttrIndex contains NaN or Infinity: $v"
                    }

                    v
                }
            }

            val labels = IntArray(dataset.labels.size) { i ->
                if (dataset.labels[i] == 1) 1 else 0
            }

            return CountingKdTreeOracle(
                points = points,
                labels = labels,
                attributes = attributes,
                globalBounds = globalBounds,
                leafSize = leafSize
            )
        }
    }

    val dims: Int = attributes.size

    private val baseMin: DoubleArray =
        DoubleArray(dims) { localDim ->
            globalBounds[attributes[localDim]][0]
        }

    private val baseMax: DoubleArray =
        DoubleArray(dims) { localDim ->
            globalBounds[attributes[localDim]][1]
        }

    private val queryBuffers: ThreadLocal<Pair<DoubleArray, DoubleArray>> =
        ThreadLocal.withInitial {
            Pair(
                DoubleArray(dims),
                DoubleArray(dims)
            )
        }

    private val attributeToLocalDim: IntArray = run {
        val maxAttr = attributes.maxOrNull()
            ?: error("attributes must not be empty")

        val map = IntArray(maxAttr + 1) { -1 }

        for ((localDim, originalAttrIndex) in attributes.withIndex()) {
            map[originalAttrIndex] = localDim
        }

        map
    }

    private sealed class Node {
        abstract val minBounds: DoubleArray
        abstract val maxBounds: DoubleArray
        abstract val count: Int
        abstract val positiveCount: Int
    }

    private class LeafNode(
        val indices: IntArray,
        override val minBounds: DoubleArray,
        override val maxBounds: DoubleArray,
        override val count: Int,
        override val positiveCount: Int
    ) : Node()

    private class InternalNode(
        val left: Node,
        val right: Node,
        override val minBounds: DoubleArray,
        override val maxBounds: DoubleArray,
        override val count: Int,
        override val positiveCount: Int
    ) : Node()

    private val root: Node? = if (points.isEmpty()) {
        null
    } else {
        val indices = IntArray(points.size) { it }
        build(indices, 0, indices.size)
    }

    private fun fillQueryBounds(
        genotype: Genotype<AttributeGene>,
        min: DoubleArray,
        max: DoubleArray
    ): Boolean {
        baseMin.copyInto(min)
        baseMax.copyInto(max)

        var hasActiveGene = false
        val lhs = genotype[0] as RuleSideChromosome

        for (i in 0 until lhs.length()) {
            val g = lhs[i]

            if (!g.isDefault) {
                hasActiveGene = true

                val localDim = localDimensionOfFast(g.attributeIndex)

                min[localDim] = g.lowerBound
                max[localDim] = g.upperBound

                if (min[localDim] > max[localDim]) {
                    return false
                }
            }
        }

        return hasActiveGene
    }

    private fun countSupportOnly(
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): Int {
        require(queryMin.size == dims)
        require(queryMax.size == dims)

        val rootNode = root ?: return 0

        var support = 0

        fun visit(node: Node) {
            if (!intersects(node.minBounds, node.maxBounds, queryMin, queryMax)) {
                return
            }

            if (containedBy(node.minBounds, node.maxBounds, queryMin, queryMax)) {
                support += node.count
                return
            }

            when (node) {
                is LeafNode -> {
                    val idxs = node.indices

                    for (i in idxs.indices) {
                        val rowIndex = idxs[i]
                        val p = points[rowIndex]

                        if (pointInside(p, queryMin, queryMax)) {
                            support++
                        }
                    }
                }

                is InternalNode -> {
                    visit(node.left)
                    visit(node.right)
                }
            }
        }

        visit(rootNode)

        return support
    }

    fun evaluate(genotype: Genotype<AttributeGene>): KdCountResult {
        val (min, max) = queryBuffers.get()

        val hasActiveGene = fillQueryBounds(
            genotype = genotype,
            min = min,
            max = max
        )

        if (!hasActiveGene) {
            return KdCountResult(
                support = 0,
                positiveSupport = 0
            )
        }

        return countRange(min, max)
    }

    fun supportOf(genotype: Genotype<AttributeGene>): Int {
        val (min, max) = queryBuffers.get()

        val hasActiveGene = fillQueryBounds(
            genotype = genotype,
            min = min,
            max = max
        )

        if (!hasActiveGene) {
            return 0
        }

        return countSupportOnly(min, max)
    }

    // test purpose only
    fun supportRange(
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): Int {
        return countSupportOnly(queryMin, queryMax)
    }

    fun countRange(
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): KdCountResult {
        require(queryMin.size == dims)
        require(queryMax.size == dims)

        val rootNode = root ?: return KdCountResult(0, 0)

        var support = 0
        var positiveSupport = 0

        fun visit(node: Node) {
            if (!intersects(node.minBounds, node.maxBounds, queryMin, queryMax)) {
                return
            }

            if (containedBy(node.minBounds, node.maxBounds, queryMin, queryMax)) {
                support += node.count
                positiveSupport += node.positiveCount
                return
            }

            when (node) {
                is LeafNode -> {
                    val idxs = node.indices

                    for (i in idxs.indices) {
                        val rowIndex = idxs[i]
                        val p = points[rowIndex]

                        if (pointInside(p, queryMin, queryMax)) {
                            support++

                            if (labels[rowIndex] == 1) {
                                positiveSupport++
                            }
                        }
                    }
                }

                is InternalNode -> {
                    visit(node.left)
                    visit(node.right)
                }
            }
        }

        visit(rootNode)

        return KdCountResult(support, positiveSupport)
    }

    private fun build(
        indices: IntArray,
        from: Int,
        to: Int
    ): Node {

        require(from < to) {
            "Invalid build range: from=$from to=$to"
        }

        val count = to - from

        val minBounds = DoubleArray(dims) { Double.POSITIVE_INFINITY }
        val maxBounds = DoubleArray(dims) { Double.NEGATIVE_INFINITY }

        var positiveCount = 0

        for (i in from until to) {
            val rowIndex = indices[i]
            val p = points[rowIndex]

            for (d in 0 until dims) {
                val v = p[d]
                if (v < minBounds[d]) minBounds[d] = v
                if (v > maxBounds[d]) maxBounds[d] = v
            }

            if (labels[rowIndex] == 1) {
                positiveCount++
            }
        }

        if (count <= leafSize) {
            return LeafNode(
                indices = indices.copyOfRange(from, to),
                minBounds = minBounds,
                maxBounds = maxBounds,
                count = count,
                positiveCount = positiveCount
            )
        }

        val splitDim = widestDimension(minBounds, maxBounds)

        indices.sortRangeByCoordinate(
            from = from,
            to = to,
            dim = splitDim,
            points = points
        )

        val mid = (from + to) ushr 1

        val left = build(indices, from, mid)
        val right = build(indices, mid, to)

        return InternalNode(
            left = left,
            right = right,
            minBounds = minBounds,
            maxBounds = maxBounds,
            count = count,
            positiveCount = positiveCount
        )
    }

    private fun widestDimension(
        minBounds: DoubleArray,
        maxBounds: DoubleArray
    ): Int {
        var bestDim = 0
        var bestWidth = maxBounds[0] - minBounds[0]

        for (d in 1 until dims) {
            val width = maxBounds[d] - minBounds[d]

            if (width > bestWidth) {
                bestWidth = width
                bestDim = d
            }
        }

        return bestDim
    }

    private fun pointInside(
        point: DoubleArray,
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): Boolean {
        for (d in 0 until dims) {
            val v = point[d]

            if (v < queryMin[d] || v > queryMax[d]) {
                return false
            }
        }

        return true
    }

    private fun intersects(
        nodeMin: DoubleArray,
        nodeMax: DoubleArray,
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): Boolean {
        for (d in 0 until dims) {
            if (nodeMax[d] < queryMin[d] || nodeMin[d] > queryMax[d]) {
                return false
            }
        }

        return true
    }

    private fun containedBy(
        nodeMin: DoubleArray,
        nodeMax: DoubleArray,
        queryMin: DoubleArray,
        queryMax: DoubleArray
    ): Boolean {
        for (d in 0 until dims) {
            if (nodeMin[d] < queryMin[d] || nodeMax[d] > queryMax[d]) {
                return false
            }
        }

        return true
    }

    private fun localDimensionOfFast(attributeIndex: Int): Int {
        return attributeToLocalDim[attributeIndex]
    }

    override fun close() {
        // Nothing to close. Kept for same .use { } pattern as Lucene/TinSpin.
    }
}
