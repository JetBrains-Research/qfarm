package org.jetbrains.bio.qfarm

import java.io.File
import java.io.BufferedReader

data class DatasetWithHeader(
    val header: List<String>,
    val data: List<DoubleArray>
)

private fun pickDelimiter(headerLine: String): Char {
    // Choose the most frequent among ',', '\t', ';' in the header line.
    val candidates = charArrayOf(',', '\t', ';')
    return candidates.maxBy { c -> headerLine.count { it == c } }.let { chosen ->
        // Fallback to comma if nothing found.
        if (headerLine.indexOf(chosen) >= 0) chosen else ','
    }
}

fun loadDatasetSubset(
    filePath: String,
    maxRows: Int = hp.maxDatasetRows,
    maxCols: Int = hp.maxDatasetCols,
    excludeColumns: Set<String> = emptySet()
): DatasetWithHeader {
    val file = File(filePath)
    if (!file.exists()) throw IllegalArgumentException("CSV not found: $filePath")

    BufferedReader(file.reader()).use { br ->
        val firstLine = br.readLine() ?: throw IllegalArgumentException("CSV is empty!")
        val delimiter = pickDelimiter(firstLine)

        // Build full header and decide which columns to keep
        val fullHeader = firstLine.split(delimiter)
            .map { it.trim() }
            .map {it.removeSurrounding("\"")}
        if (fullHeader.isEmpty()) throw IllegalArgumentException("Header row has no columns.")

        // Indices to keep: exclude requested columns, then cap by maxCols
        val keepIndices = fullHeader.withIndex()
            .asSequence()
            .filter { (_, name) -> name !in excludeColumns }
            .map { it.index }
            .take(maxCols)
            .toList()

        if (keepIndices.isEmpty()) {
            throw IllegalArgumentException("No columns left after applying excludeColumns/maxCols.")
        }

        val header = keepIndices.map { idx -> fullHeader[idx] }

        val dataset = ArrayList<DoubleArray>(minOf(30_024, maxRows))
        var rowsRead = 0

        // Stream data lines; stop at maxRows or EOF
        sequence {
            while (true) {
                val line = br.readLine() ?: break
                yield(line)
            }
        }.forEach { line ->
            if (rowsRead >= maxRows) return@forEach

            if (line.isBlank()) return@forEach
            val cells = line.split(delimiter)

            // Quick length check—skip if line too short
            // (keeps us from getOrNull and extra bounds checks)
            if (cells.size <= keepIndices.last()) return@forEach

            val row = DoubleArray(keepIndices.size)
            var ok = true

            // Parse only the kept indices; trim just those cells
            var j = 0
            while (j < keepIndices.size) {
                val idx = keepIndices[j]
                val raw = cells[idx].trim()
                val v = raw.toDoubleOrNull()
                if (v == null) { ok = false; break }
                row[j] = v
                j++
            }

            if (ok) {
                dataset.add(row)
                rowsRead++
            }
        }

        return DatasetWithHeader(header = header, data = dataset)
    }
}

