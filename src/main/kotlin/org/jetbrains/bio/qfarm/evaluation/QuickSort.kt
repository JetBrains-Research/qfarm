package org.jetbrains.bio.qfarm.evaluation

fun IntArray.sortRangeByCoordinate(
    from: Int,
    to: Int,
    dim: Int,
    points: Array<DoubleArray>
) {
    quickSortByCoordinate(
        array = this,
        left = from,
        right = to - 1,
        dim = dim,
        points = points
    )
}

private fun quickSortByCoordinate(
    array: IntArray,
    left: Int,
    right: Int,
    dim: Int,
    points: Array<DoubleArray>
) {
    var i = left
    var j = right
    val pivot = points[array[(left + right) ushr 1]][dim]

    while (i <= j) {
        while (points[array[i]][dim] < pivot) i++
        while (points[array[j]][dim] > pivot) j--

        if (i <= j) {
            val tmp = array[i]
            array[i] = array[j]
            array[j] = tmp
            i++
            j--
        }
    }

    if (left < j) {
        quickSortByCoordinate(array, left, j, dim, points)
    }

    if (i < right) {
        quickSortByCoordinate(array, i, right, dim, points)
    }
}
