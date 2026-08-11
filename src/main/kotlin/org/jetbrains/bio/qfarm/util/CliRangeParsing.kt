package org.jetbrains.bio.qfarm.util

fun parseAbsoluteRange(
    arg: String
): Pair<Double?, Double?> {

    val parts = normalizeRange(arg)

    fun parseLower(value: String): Double? =
        when (value.uppercase()) {
            "MIN" -> null

            "MAX" ->
                error(
                    "Invalid lower range endpoint 'MAX'. " +
                            "Use a number or MIN."
                )

            else ->
                value.toDoubleOrNull()
                    ?: error(
                        "Invalid lower range endpoint '$value'. " +
                                "Use a number or MIN."
                    )
        }

    fun parseUpper(value: String): Double? =
        when (value.uppercase()) {
            "MAX" -> null

            "MIN" ->
                error(
                    "Invalid upper range endpoint 'MIN'. " +
                            "Use a number or MAX."
                )

            else ->
                value.toDoubleOrNull()
                    ?: error(
                        "Invalid upper range endpoint '$value'. " +
                                "Use a number or MAX."
                    )
        }

    val lower = parseLower(parts[0])
    val upper = parseUpper(parts[1])

    if (lower != null && upper != null) {
        require(lower <= upper) {
            "Lower range endpoint must not exceed upper range endpoint."
        }
    }

    return lower to upper
}


fun parsePercentileRange(
    arg: String
): Pair<Double, Double> {

    val parts = normalizeRange(arg)

    val lower =
        parts[0].toDoubleOrNull()
            ?: error(
                "Invalid lower percentile '${parts[0]}'. " +
                        "Expected a number between 0 and 100."
            )

    val upper =
        parts[1].toDoubleOrNull()
            ?: error(
                "Invalid upper percentile '${parts[1]}'. " +
                        "Expected a number between 0 and 100."
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


private fun normalizeRange(
    arg: String
): List<String> {

    val cleaned =
        arg.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .replace("..", ",")

    val parts =
        cleaned.split(",")
            .map { it.trim() }

    require(parts.size == 2) {
        "Invalid range '$arg'. Expected LOW,HIGH or LOW..HIGH."
    }

    require(parts.none { it.isEmpty() }) {
        "Invalid range '$arg'. Both endpoints must be specified."
    }

    return parts
}
