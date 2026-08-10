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

    val maxNameLength =
        dataset.header.maxOf { it.length }

    // Compute all formatted quantile values first.
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

    /*
     * Find the longest value that actually occurs in this dataset.
     *
     * We add one extra character so neighbouring quantiles don't touch,
     * but avoid the large fixed spacing from the previous version.
     */
    val valueWidth =
        formattedValues
            .flatten()
            .maxOf { it.length } + 1

    for (columnIndex in dataset.header.indices) {

        val quantileText =
            CHECK_QUANTILES
                .mapIndexed { quantileIndex, quantile ->

                    val value =
                        formattedValues[columnIndex][quantileIndex]

                    /*
                     * Left-align the value.
                     *
                     * q labels are NOT padded. Only the value field gets
                     * enough space to keep the following quantile aligned.
                     */
                    "${YELLOW}${quantile.label}$RESET: " +
                            value.padEnd(valueWidth)
                }
                .joinToString(" ")

        val columnName =
            dataset.header[columnIndex]
                .padEnd(maxNameLength)

        println(
            "${CYAN}$columnName$RESET  $quantileText"
        )
    }
}
