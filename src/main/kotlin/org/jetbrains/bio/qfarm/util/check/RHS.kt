package org.jetbrains.bio.qfarm.util.check

import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.util.parseAbsoluteRange
import org.jetbrains.bio.qfarm.util.parsePercentileRange

fun resolveCheckRhsRange(
    rhsRangeArg: String?,
    rhsPctArg: String?,
    rhsIndex: Int,
    min: Double,
    max: Double,
    percentileProvider: SortedColumnsPercentileProvider
): Pair<Double, Double>? {

    if (rhsRangeArg == null && rhsPctArg == null) {
        return null
    }

    if (rhsRangeArg != null) {

        val (lowerOpt, upperOpt) =
            parseAbsoluteRange(rhsRangeArg)

        return (lowerOpt ?: min) to
                (upperOpt ?: max)
    }

    val (lowerPercentile, upperPercentile) =
        parsePercentileRange(rhsPctArg!!)

    return percentileProvider.value(
        rhsIndex,
        lowerPercentile / 100.0
    ) to percentileProvider.value(
        rhsIndex,
        upperPercentile / 100.0
    )
}
