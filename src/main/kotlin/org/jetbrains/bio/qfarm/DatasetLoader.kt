package org.jetbrains.bio.qfarm

import tech.tablesaw.api.*
import tech.tablesaw.columns.Column

// ---------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------
data class DatasetWithHeader(
    val header: List<String>,
    val data: List<DoubleArray>
)

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------
private fun isNumericColumn(col: Column<*>): Boolean =
    col is DoubleColumn ||
            col is IntColumn ||
            col is LongColumn ||
            col is FloatColumn ||
            col is ShortColumn

private fun getAsDouble(col: Column<*>, row: Int): Double = when (col) {
    is DoubleColumn -> col.getDouble(row)
    is IntColumn    -> col.get(row).toDouble()
    is LongColumn   -> col.get(row).toDouble()
    is FloatColumn  -> col.get(row).toDouble()
    is ShortColumn  -> col.get(row).toDouble()
    else -> Double.NaN // unreachable if filtered correctly
}

// ---------------------------------------------------------------------
// Dataset loader
// ---------------------------------------------------------------------
fun loadNumericDataset(
    filePath: String,
    excludeColumns: Set<String> = emptySet()
): DatasetWithHeader {

    val table = Table.read().csv(filePath)
    // The following are considered NaN: "", N/A, null, NaN, NA,

    println("=== Dataset parsing ===")
    println("Columns present (${table.columnCount()}):")
    table.columnNames().forEach { println("  - $it") }

    val keptColumns = mutableListOf<Column<*>>()   // actual column objects
    val keptNames = mutableListOf<String>()        // column names (header)
    val ignoredColumns = mutableListOf<String>()
    val nonNaCounts = mutableMapOf<String, Int>()

    // -----------------------------------------------------------------
    // Column-wise validation
    // -----------------------------------------------------------------
    for (col in table.columns()) {
        val name = col.name()

        // 1) User-excluded columns (argument)
        if (name in excludeColumns) {
            ignoredColumns.add("$name (explicitly excluded)")
            continue
        }

        // 2) Non-numeric columns
        if (!isNumericColumn(col)) {
            ignoredColumns.add("$name (non-numeric type: ${col.type().name()})")
            continue
        }

        // 3) Scan values: allow NaN, reject non-finite
        var nonNa = 0
        var valid = true

        for (r in 0 until table.rowCount()) {
            val v = getAsDouble(col, r)

            if (v.isNaN()) continue
            if (!v.isFinite()) {
                valid = false
                break
            }
            nonNa++
        }

        if (valid && nonNa > 0) {
            keptColumns.add(col)
            keptNames.add(name)
            nonNaCounts[name] = nonNa
        } else if (valid && nonNa == 0) {
            // covered by !isNumericColumn as TEXT col, but just in case
            ignoredColumns.add("$name (all values are NaN)")
        } else {
            ignoredColumns.add("$name (contains invalid numeric values)")
        }
    }

    // -----------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------
    println("\nIgnored columns:")
    if (ignoredColumns.isEmpty()) {
        println("  (none)")
    } else {
        ignoredColumns.forEach { println("  - $it") }
    }

    println("\nNumeric columns kept:")
    keptNames.forEach {
        println("  - $it (non-NA values: ${nonNaCounts[it]})")
    }

    require(keptColumns.isNotEmpty()) {
        "No numeric columns left after filtering."
    }

    // -----------------------------------------------------------------
    // Build GA-friendly matrix (rows × cols)
    // -----------------------------------------------------------------
    val data = List(table.rowCount()) { r ->
        DoubleArray(keptColumns.size) { c ->
            getAsDouble(keptColumns[c], r)
        }
    }

    println("\nFinal dataset shape: rows=${data.size}, cols=${keptColumns.size}")
    println("=======================\n")

    return DatasetWithHeader(
        header = keptNames,
        data = data
    )
}

fun printFirstRows(dataset: DatasetWithHeader, n: Int = 5) {
    val rows = dataset.data.take(n)

    // Print header
    println(dataset.header.joinToString(prefix = "| ", postfix = " |", separator = " | "))

    // Print rows
    for (row in rows) {
        println(
            row.joinToString(
                prefix = "| ",
                postfix = " |",
                separator = " | "
            ) { v ->
                if (v.isNaN()) "NaN" else "%.4f".format(v)
            }
        )
    }
}
