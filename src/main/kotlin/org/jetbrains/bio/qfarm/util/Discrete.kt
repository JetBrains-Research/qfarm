package org.jetbrains.bio.qfarm.util

data class DiscreteColumnInfo(
    val isDiscrete: BooleanArray,
    val values: Array<DoubleArray?>
)

fun detectDiscreteColumns(
    sortedColumns: List<DoubleArray>,
    maxDistinct: Int = 16
): DiscreteColumnInfo {
    val isDiscrete = BooleanArray(sortedColumns.size)
    val values = arrayOfNulls<DoubleArray>(sortedColumns.size)

    for (c in sortedColumns.indices) {
        val col = sortedColumns[c]
        if (col.isEmpty()) continue

        val distinct = ArrayList<Double>(maxDistinct + 1)

        var prev = col[0]
        distinct.add(prev)

        for (i in 1 until col.size) {
            val v = col[i]

            if (v != prev) {
                distinct.add(v)
                prev = v

                if (distinct.size > maxDistinct) {
                    break
                }
            }
        }

        if (distinct.size <= maxDistinct) {
            isDiscrete[c] = true
            values[c] = distinct.toDoubleArray()
        }
    }

    return DiscreteColumnInfo(
        isDiscrete = isDiscrete,
        values = values
    )
}
