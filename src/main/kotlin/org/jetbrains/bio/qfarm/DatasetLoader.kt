package org.jetbrains.bio.qfarm

import com.univocity.parsers.csv.*
import java.io.*
import java.util.zip.GZIPInputStream


// ---------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------
data class DatasetWithHeader(
    val header: List<String>,
    val data: List<DoubleArray>
)

fun delimiterFor(filePath: String): Char =
    when {
        filePath.endsWith(".tsv") || filePath.endsWith(".tsv.gz") -> '\t'
        filePath.endsWith(".csv") || filePath.endsWith(".csv.gz") -> ','
        else -> error("Unknown dataset format: $filePath")
    }


// ---------------------------------------------------------------------
// Dataset loader
// ---------------------------------------------------------------------
fun loadNumericDataset(
    filePath: String,
    excludeColumns: Set<String> = emptySet()
): DatasetWithHeader {

    // -----------------------------------------------------------------
    // Reader (supports .gz)
    // -----------------------------------------------------------------
    val reader: Reader =
        if (filePath.endsWith(".gz")) {
            BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(filePath))))
        } else {
            BufferedReader(FileReader(filePath))
        }

    // -----------------------------------------------------------------
    // UniVocity parser configuration (wide tables)
    // -----------------------------------------------------------------
    val settings = CsvParserSettings().apply {
        format.delimiter = delimiterFor(filePath)
        isLineSeparatorDetectionEnabled = true
        maxColumns = 50_000
        nullValue = ""
        emptyValue = ""
    }

    val parser = CsvParser(settings)

    // -----------------------------------------------------------------
    // Parse all rows
    // -----------------------------------------------------------------
    val rows = parser.parseAll(reader)
    require(rows.isNotEmpty()) { "Dataset is empty" }

    val header = rows.first().toList()
    val rawData = rows.drop(1)

    val cols = header.size
    val rowsCount = rawData.size

    println("=== Dataset parsing ===")
    println("Columns present ($cols)")

    // -----------------------------------------------------------------
    // Column-wise validation
    // -----------------------------------------------------------------
    val keptIndices = mutableListOf<Int>()
    val keptNames = mutableListOf<String>()
    val ignoredColumns = mutableListOf<String>()
    val nonNaCounts = mutableMapOf<String, Int>()

    for (c in 0 until cols) {
        val name = header[c]

        if (name in excludeColumns) {
            ignoredColumns.add("$name (explicitly excluded)")
            continue
        }

        var nonNa = 0
        var valid = true

        for (r in 0 until rowsCount) {
            val cell = rawData[r].getOrNull(c)

            val v = cell?.toDoubleOrNull()
            if (v == null) {
                if (!cell.isNullOrBlank()) {
                    valid = false
                    break
                }
            } else if (v.isFinite()) {
                nonNa++
            }
        }

        if (valid && nonNa > 0) {
            keptIndices.add(c)
            keptNames.add(name)
            nonNaCounts[name] = nonNa
        } else if (valid && nonNa == 0) {
            ignoredColumns.add("$name (all values are NaN)")
        } else {
            ignoredColumns.add("$name (contains non-numeric values)")
        }
    }

    // -----------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------
    if (ignoredColumns.size < 100) {
        println("\nIgnored columns:")
        if (ignoredColumns.isEmpty()) println("  (none)")
        else ignoredColumns.forEach { println("  - $it") }
    }

    if (keptNames.size < 100) {
        println("\nNumeric columns kept:")
        keptNames.forEach {
            println("  - $it (non-NA values: ${nonNaCounts[it]})")
        }
    }

    require(keptIndices.isNotEmpty()) {
        "No numeric columns left after filtering."
    }

    // -----------------------------------------------------------------
    // Build GA-friendly matrix
    // -----------------------------------------------------------------
    val data = List(rowsCount) { r ->
        DoubleArray(keptIndices.size) { j ->
            val cell = rawData[r].getOrNull(keptIndices[j])
            cell?.toDoubleOrNull() ?: Double.NaN
        }
    }

    println("\nFinal dataset shape: rows=${data.size}, cols=${keptIndices.size}")
    println("=======================\n")

    return DatasetWithHeader(
        header = keptNames,
        data = data
    )
}

fun printFirstRows(dataset: DatasetWithHeader, n: Int = 1) {
    val rows = dataset.data.take(n)

    println(dataset.header.joinToString(prefix = "| ", postfix = " |", separator = " | "))

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
