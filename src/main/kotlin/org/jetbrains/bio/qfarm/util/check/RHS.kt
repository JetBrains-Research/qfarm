package org.jetbrains.bio.qfarm.util.check

import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider

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
            parseCheckAbsoluteRange(rhsRangeArg)

        return (lowerOpt ?: min) to
                (upperOpt ?: max)
    }

    val (lowerPercentile, upperPercentile) =
        parseCheckPercentileRange(rhsPctArg!!)

    return percentileProvider.value(
        rhsIndex,
        lowerPercentile / 100.0
    ) to percentileProvider.value(
        rhsIndex,
        upperPercentile / 100.0
    )
}

fun parseCheckAbsoluteRange(
    arg: String
): Pair<Double?, Double?> {

    val cleaned =
        arg.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .replace("..", ",")

    val parts =
        cleaned.split(",")
            .map { it.trim() }

    require(parts.size == 2) {
        "Invalid RHS range '$arg'. Expected LOW,HIGH or LOW..HIGH."
    }

    fun parseLower(value: String): Double? =
        when (value.uppercase()) {
            "MIN" -> null
            else ->
                value.toDoubleOrNull()
                    ?: error(
                        "Invalid lower RHS value '$value'."
                    )
        }

    fun parseUpper(value: String): Double? =
        when (value.uppercase()) {
            "MAX" -> null
            else ->
                value.toDoubleOrNull()
                    ?: error(
                        "Invalid upper RHS value '$value'."
                    )
        }

    return parseLower(parts[0]) to
            parseUpper(parts[1])
}

fun parseCheckPercentileRange(
    arg: String
): Pair<Double, Double> {

    val cleaned =
        arg.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .replace("..", ",")

    val parts =
        cleaned.split(",")
            .map { it.trim() }

    require(parts.size == 2) {
        "Invalid percentile range '$arg'. Expected LOW,HIGH."
    }

    val lower =
        parts[0].toDoubleOrNull()
            ?: error(
                "Invalid lower percentile '${parts[0]}'."
            )

    val upper =
        parts[1].toDoubleOrNull()
            ?: error(
                "Invalid upper percentile '${parts[1]}'."
            )

    require(lower in 0.0..100.0) {
        "Lower percentile must be between 0 and 100."
    }

    require(upper in 0.0..100.0) {
        "Upper percentile must be between 0 and 100."
    }

    require(lower <= upper) {
        "Lower percentile must not exceed upper percentile."
    }

    return lower to upper
}
