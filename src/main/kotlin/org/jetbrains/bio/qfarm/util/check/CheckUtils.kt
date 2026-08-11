package org.jetbrains.bio.qfarm.util.check

import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.params.BLUE
import org.jetbrains.bio.qfarm.params.CYAN
import org.jetbrains.bio.qfarm.params.GREEN
import org.jetbrains.bio.qfarm.params.RED
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.YELLOW
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import java.util.Locale

data class CheckQuantile(
    val label: String,
    val percentile: Double
)

private const val MAX_COLUMN_NAME_WIDTH = 30

val CHECK_QUANTILES =
    listOf(
        CheckQuantile("q0.1", 0.001),
        CheckQuantile("q1", 0.01),
        CheckQuantile("q5", 0.05),
        CheckQuantile("q10", 0.10),
        CheckQuantile("q25", 0.25),
        CheckQuantile("q50", 0.50),
        CheckQuantile("q75", 0.75),
        CheckQuantile("q90", 0.90),
        CheckQuantile("q95", 0.95),
        CheckQuantile("q99", 0.99),
        CheckQuantile("q99.9", 0.999)
    )

sealed interface CheckIssue {

    val message: String

    data class Warning(
        override val message: String
    ) : CheckIssue

    data class Error(
        override val message: String
    ) : CheckIssue
}

fun List<CheckIssue>.warnings(): List<String> =
    filterIsInstance<CheckIssue.Warning>()
        .map { it.message }

fun List<CheckIssue>.errors(): List<String> =
    filterIsInstance<CheckIssue.Error>()
        .map { it.message }

fun printCheckResult(
    errors: List<String>,
    warnings: List<String>
) {

    println()
    println("${BLUE}=== QFARM check ===$RESET")

    when {
        errors.isNotEmpty() -> {
            println("STATUS: ${RED}ERROR$RESET")
            println()
            println("Dataset/configuration is not ready for QFARM.")

            errors.forEach {
                println("  ERROR: $it")
            }

            warnings.forEach {
                println("  WARNING: $it")
            }
        }

        warnings.isNotEmpty() -> {
            println("STATUS: ${YELLOW}WARNING$RESET")
            println()
            println("Dataset is usable, but potential issues were detected.")

            warnings.forEach {
                println("  WARNING: $it")
            }
        }

        else -> {
            println("STATUS: ${GREEN}READY$RESET")
            println("Dataset is ready for QFARM.")
        }
    }
}

fun formatCheckNumber(value: Double): String {

    if (value.isNaN()) {
        return "NaN"
    }

    if (value == Double.POSITIVE_INFINITY) {
        return "Infinity"
    }

    if (value == Double.NEGATIVE_INFINITY) {
        return "-Infinity"
    }

    return "%,.5f".format(Locale.US, value)
        .trimEnd('0')
        .trimEnd('.')
}

fun printColumnQuantiles(
    dataset: DatasetWithHeader,
    percentileProvider: SortedColumnsPercentileProvider
) {
    println()
    println("${BLUE}=== Column quantiles ===$RESET")
    println()

    val columnNameWidth =
        minOf(
            MAX_COLUMN_NAME_WIDTH,
            dataset.header.maxOf { it.length }
        )

    // Precompute all formatted quantile values.
    val formattedValues =
        dataset.header.indices.map { columnIndex ->
            CHECK_QUANTILES.map { quantile ->
                formatCheckNumber(
                    percentileProvider.value(
                        attributeIndex = columnIndex,
                        percentile = quantile.percentile
                    )
                )
            }
        }

    // Width of each quantile column.
    val quantileWidths =
        CHECK_QUANTILES.indices.map { quantileIndex ->

            val headerWidth =
                CHECK_QUANTILES[quantileIndex].label.length

            val valueWidth =
                formattedValues.maxOf {
                    it[quantileIndex].length
                }

            maxOf(
                headerWidth,
                valueWidth,
                6
            )
        }

    // -------------------------------------------------------------
    // Header
    // -------------------------------------------------------------

    val header =
        buildString {

            append(
                " ".padEnd(columnNameWidth)
            )

            append("  ")

            CHECK_QUANTILES.forEachIndexed { index, quantile ->

                val paddedLabel =
                    quantile.label.padEnd(
                        quantileWidths[index]
                    )

                append(
                    "${YELLOW}$paddedLabel$RESET"
                )

                if (index != CHECK_QUANTILES.lastIndex) {
                    append("  ")
                }
            }
        }

    println(header)

    // -------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------

    dataset.header.indices.forEach { columnIndex ->

        val shortName =
            shortenColumnName(
                name = dataset.header[columnIndex],
                maxWidth = columnNameWidth
            )

        val row =
            buildString {

                val paddedName =
                    shortName.padEnd(columnNameWidth)

                append(
                    "${CYAN}$paddedName$RESET"
                )

                append("  ")

                CHECK_QUANTILES.indices.forEach { quantileIndex ->

                    val value =
                        formattedValues[columnIndex][quantileIndex]

                    append(
                        value.padEnd(
                            quantileWidths[quantileIndex]
                        )
                    )

                    if (quantileIndex != CHECK_QUANTILES.lastIndex) {
                        append("  ")
                    }
                }
            }

        println(row)
    }
}

private fun shortenColumnName(
    name: String,
    maxWidth: Int
): String {

    if (name.length <= maxWidth) {
        return name
    }

    require(maxWidth >= 7) {
        "Column name width must be at least 7."
    }

    val ellipsis = "..."

    val available =
        maxWidth - ellipsis.length

    // Keep more of the beginning than the end.
    val leftLength =
        (available * 2) / 3

    val rightLength =
        available - leftLength

    return buildString {
        append(
            name.take(leftLength)
        )

        append(ellipsis)

        append(
            name.takeLast(rightLength)
        )
    }
}
