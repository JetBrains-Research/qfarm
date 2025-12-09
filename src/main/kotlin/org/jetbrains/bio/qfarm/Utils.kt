package org.jetbrains.bio.qfarm

import java.util.Arrays
import kotlin.math.pow
import kotlin.math.round
import java.util.stream.IntStream


fun computeSortedColumns(dataset: List<DoubleArray>): List<DoubleArray> {
    require(dataset.isNotEmpty()) { "dataset is empty" }

    val rows = dataset.size
    val cols = dataset[0].size

    // Validate rectangular dataset
    if (dataset.any { it.size != cols }) {
        throw IllegalArgumentException("Inconsistent row lengths in dataset")
    }

    // Transpose: columns[c][r] = dataset[r][c]
    val columns = Array(cols) { DoubleArray(rows) }
    for (r in 0 until rows) {
        val row = dataset[r]
        for (c in 0 until cols) {
            columns[c][r] = row[c]
        }
    }

    // Sort columns (parallel for large problems)
    if (cols > 8 && rows > 10_000) {
        IntStream.range(0, cols).parallel().forEach { i ->
            Arrays.sort(columns[i])
        }
    } else {
        for (c in 0 until cols) Arrays.sort(columns[c])
    }

    return columns.asList()
}


fun cumulativePercentage(sortedValues: DoubleArray, threshold: Double): Double {
    require(sortedValues.isNotEmpty()) { "Empty array" }

    // Since the array is sorted ascending, we can stop early using binary search
    val idx = sortedValues.binarySearch(threshold)

    // binarySearch returns:
    //  - exact index if found (>= 0)
    //  - negative insertion point (-insertionIndex - 1) if not found
    val count = if (idx >= 0) {
        // move forward to include duplicates equal to threshold
        var i = idx + 1
        while (i < sortedValues.size && sortedValues[i] <= threshold) i++
        i
    } else {
        val insertionPoint = -idx - 1
        insertionPoint
    }

    return (count.toDouble() / sortedValues.size) * 100.0
}


fun computeBoundsFromSorted(sortedColumns: List<DoubleArray>): Array<DoubleArray> {
    require(sortedColumns.isNotEmpty()) { "No columns to process" }

    val out = Array(sortedColumns.size) { DoubleArray(2) }
    for (i in sortedColumns.indices) {
        val col = sortedColumns[i]
        require(col.isNotEmpty()) { "Encountered empty column at index $i" }
        out[i][0] = col[0]                    // min
        out[i][1] = col[col.lastIndex]        // max
    }
    return out
}


fun Double.roundTo(n: Int): Double {
    val factor = 10.0.pow(n)
    return round(this * factor) / factor
}
