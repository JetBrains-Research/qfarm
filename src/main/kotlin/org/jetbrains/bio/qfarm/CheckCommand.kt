package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.params.BLUE
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.util.check.CheckIssue
import org.jetbrains.bio.qfarm.util.check.errors
import org.jetbrains.bio.qfarm.util.check.formatCheckNumber
import org.jetbrains.bio.qfarm.util.check.printCheckResult
import org.jetbrains.bio.qfarm.util.check.printColumnQuantiles
import org.jetbrains.bio.qfarm.util.check.resolveCheckRhsRange
import org.jetbrains.bio.qfarm.util.check.warnings
import org.jetbrains.bio.qfarm.util.computeBoundsFromSorted
import org.jetbrains.bio.qfarm.util.computeLabelsFast
import org.jetbrains.bio.qfarm.util.computeSortedColumns
import org.jetbrains.bio.qfarm.util.detectDiscreteColumns
import org.jetbrains.bio.qfarm.util.loadNumericDataset
import org.jetbrains.bio.qfarm.util.removeRowsWithNaNRHS

class CheckCommand : CliktCommand(name = "check") {

    override fun commandHelp(context: Context): String = """
        Check whether a dataset can be used by QFARM.

        The dataset is parsed and inspected without running rule search,
        evolution, random-AUC generation, or validation.

        Column quantiles and detected discrete columns are reported.

        The RHS is optional. When supplied, RHS-specific checks are also
        performed. An RHS range may optionally be supplied using either an
        absolute range or percentile range.
    """.trimIndent()

    override fun commandHelpEpilog(context: Context): String = """
        Examples:

          Check only the dataset:
            java -jar qfarm.jar check \
              --data data.csv

          Check the dataset and an RHS column:
            java -jar qfarm.jar check \
              --data data.csv \
              --rhs y

          Check an RHS percentile range:
            java -jar qfarm.jar check \
              --data data.csv \
              --rhs y \
              --rhs-range-percentile 80,100

          Check an absolute RHS range:
            java -jar qfarm.jar check \
              --data data.csv \
              --rhs y \
              --rhs-range 4.0,MAX
    """.trimIndent()

    private val dataPath by option(
        "--data",
        metavar = "PATH",
        help = "Path to the CSV or TSV dataset."
    ).required()

    private val rhsName by option(
        "--rhs",
        metavar = "COLUMN",
        help = "Optional right-hand-side target column."
    )

    private val rhsRangeArg by option(
        "--rhs-range",
        metavar = "LOW,HIGH",
        help = """
            Optional absolute RHS range. Accepts LOW,HIGH or LOW..HIGH.
            MIN and MAX may be used for open endpoints.
        """.trimIndent()
    )

    private val rhsPctArg by option(
        "--rhs-range-percentile",
        metavar = "LOW,HIGH",
        help = """
            Optional percentile RHS range, with values between 0 and 100.
            Accepts LOW,HIGH or LOW..HIGH.
        """.trimIndent()
    )

    private val exclColsOpt by option(
        "--excl-cols",
        metavar = "COL1,COL2,...",
        help = "Optional comma-separated columns to exclude."
    )

    override fun run() {

        val issues = mutableListOf<CheckIssue>()

        // -------------------------------------------------------------
        // CLI consistency
        // -------------------------------------------------------------

        if (rhsRangeArg != null && rhsPctArg != null) {
            printCheckResult(
                errors = listOf(
                    "Only one of --rhs-range and --rhs-range-percentile may be specified."
                ),
                warnings = emptyList()
            )
            throw ProgramResult(1)
        }

        if (rhsName == null && (rhsRangeArg != null || rhsPctArg != null)) {
            printCheckResult(
                errors = listOf(
                    "--rhs must be specified when an RHS range is provided."
                ),
                warnings = emptyList()
            )
            throw ProgramResult(1)
        }

        val excludedColumns =
            exclColsOpt
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

        // -------------------------------------------------------------
        // Dataset loading
        // -------------------------------------------------------------

        var dataset = try {
            loadNumericDataset(
                filePath = dataPath,
                excludeColumns = excludedColumns
            )
        } catch (e: Exception) {
            printCheckResult(
                errors = listOf(
                    e.message ?: "Dataset could not be loaded."
                ),
                warnings = emptyList()
            )
            throw ProgramResult(1)
        }

        // -------------------------------------------------------------
        // Optional RHS
        //
        // Match initEnvironment behaviour: if RHS is given, rows with
        // NaN RHS are removed BEFORE sorted columns are calculated.
        // -------------------------------------------------------------

        val rhsIndex: Int? =
            if (rhsName != null) {
                val index = dataset.header.indexOf(rhsName)

                if (index < 0) {
                    printCheckResult(
                        errors = listOf(
                            "Right-hand-side column '$rhsName' was not found among the numeric columns."
                        ),
                        warnings = emptyList()
                    )
                    throw ProgramResult(1)
                }

                dataset = removeRowsWithNaNRHS(
                    dataset = dataset,
                    rhsIndex = index
                )

                index
            } else {
                null
            }

        // -------------------------------------------------------------
        // Non-finite value checks
        // -------------------------------------------------------------

        for (columnIndex in dataset.header.indices) {
            val name = dataset.header[columnIndex]

            val infiniteCount =
                dataset.data.count { row ->
                    val value = row[columnIndex]
                    !value.isNaN() && !value.isFinite()
                }

            if (infiniteCount > 0) {
                issues += CheckIssue.Error(
                    "$name contains $infiniteCount non-finite value(s) " +
                            "(Infinity or -Infinity)."
                )
            }
        }

        // -------------------------------------------------------------
        // Same structures used by initEnvironment
        // -------------------------------------------------------------

        val sortedColumns = try {
            computeSortedColumns(dataset.data)
        } catch (e: Exception) {
            printCheckResult(
                errors =
                    issues.errors() +
                            (e.message ?: "Could not compute sorted columns."),
                warnings =
                    issues.warnings()
            )
            throw ProgramResult(1)
        }

        val bounds =
            computeBoundsFromSorted(sortedColumns)

        val percentileProvider =
            SortedColumnsPercentileProvider(sortedColumns)

        val discreteInfo =
            detectDiscreteColumns(
                sortedColumns = sortedColumns,
                maxDistinct = 16
            )

        // -------------------------------------------------------------
        // General dataset checks
        // -------------------------------------------------------------

        for (columnIndex in dataset.header.indices) {

            val name =
                dataset.header[columnIndex]

            val totalRows =
                dataset.data.size

            val availableValues =
                sortedColumns[columnIndex].size

            val missing =
                totalRows - availableValues

            if (missing > 0) {
                val percentage =
                    if (totalRows == 0) {
                        0.0
                    } else {
                        missing.toDouble() / totalRows * 100.0
                    }

                issues += CheckIssue.Warning(
                    "$name contains $missing missing value(s) " +
                            "(${formatCheckNumber(percentage)}%)."
                )
            }

            val min =
                bounds[columnIndex][0]

            val max =
                bounds[columnIndex][1]

            if (min == max) {
                issues += CheckIssue.Warning(
                    "$name is constant: all usable values are ${formatCheckNumber(min)}."
                )
            }
        }

        // -------------------------------------------------------------
        // Quantiles
        // -------------------------------------------------------------

        printColumnQuantiles(
            dataset = dataset,
            percentileProvider = percentileProvider
        )

        // -------------------------------------------------------------
        // Optional RHS details
        // -------------------------------------------------------------

        if (rhsIndex != null) {

            println()
            println("${BLUE}=== RHS check ===$RESET")
            println("RHS: ${dataset.header[rhsIndex]}")

            val minRhs =
                bounds[rhsIndex][0]

            val maxRhs =
                bounds[rhsIndex][1]

            println(
                "Bounds: ${formatCheckNumber(minRhs)} to " +
                        formatCheckNumber(maxRhs)
            )

            val resolvedRange =
                resolveCheckRhsRange(
                    rhsRangeArg = rhsRangeArg,
                    rhsPctArg = rhsPctArg,
                    rhsIndex = rhsIndex,
                    min = minRhs,
                    max = maxRhs,
                    percentileProvider = percentileProvider
                )

            if (resolvedRange != null) {

                val (lower, upper) =
                    resolvedRange

                if (lower > upper) {
                    issues += CheckIssue.Error(
                        "RHS lower bound must not exceed the upper bound."
                    )
                } else {

                    println(
                        "RANGE: $lower to $upper"
                    )

                    val labels =
                        computeLabelsFast(
                            dataset = dataset.data,
                            rhsIndex = rhsIndex,
                            lower = lower,
                            upper = upper
                        )

                    val positives =
                        labels.sum()

                    println(
                        "Positive labels: $positives / ${labels.size}"
                    )

                    if (positives == 0) {
                        issues += CheckIssue.Warning(
                            "The selected RHS range contains no positive rows."
                        )
                    } else if (positives == labels.size) {
                        issues += CheckIssue.Warning(
                            "The selected RHS range contains every row."
                        )
                    }
                }
            } else {
                println(
                    "No RHS range supplied; RHS column validity only was checked."
                )
            }
        }

        // -------------------------------------------------------------
        // Discrete columns
        // -------------------------------------------------------------

        println()
        println("Discrete columns detected:")

        val discreteNames =
            dataset.header.indices
                .filter {
                    discreteInfo.isDiscrete[it]
                }

        if (discreteNames.isEmpty()) {
            println("  (none)")
        } else {
            discreteNames.forEach { index ->

                val values =
                    discreteInfo.values[index]
                        ?.joinToString(
                            prefix = "[",
                            postfix = "]"
                        ) {
                            formatCheckNumber(it)
                        }

                println(
                    "  - ${dataset.header[index]}: $values"
                )
            }
        }

        // -------------------------------------------------------------
        // Final status
        // -------------------------------------------------------------

        printCheckResult(
            errors = issues.errors(),
            warnings = issues.warnings()
        )

        if (issues.any { it is CheckIssue.Error }) {
            throw ProgramResult(1)
        }
    }
}
