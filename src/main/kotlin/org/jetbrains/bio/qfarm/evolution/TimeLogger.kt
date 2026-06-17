package org.jetbrains.bio.qfarm.evolution

import java.io.File

enum class RangeSearchKind {
    FULL_SEARCH,
    CHEAP_EVOLUTION
}

data class RangeTimingRow(
    val rule: String,
    val depth: Int,
    val searchKind: RangeSearchKind,
    val timings: Map<String, Double>
)

fun writeRangeTimingsCsv(
    file: File,
    rows: List<RangeTimingRow>
) {
    if (rows.isEmpty()) return

    val timingColumns = rows
        .flatMap { it.timings.keys }
        .distinct()
        .let { keys ->
            listOf("total") + keys.filterNot { it == "total" }
        }

    file.bufferedWriter().use { writer ->
        writer.appendLine(
            (listOf("rule", "depth", "searchKind") + timingColumns)
                .joinToString(",")
        )

        rows.forEach { row ->
            val values = buildList {
                add(csvEscape(row.rule))
                add(row.depth.toString())
                add(row.searchKind.name)

                timingColumns.forEach { column ->
                    add(row.timings[column]?.toString() ?: "")
                }
            }

            writer.appendLine(values.joinToString(","))
        }
    }
}

private fun csvEscape(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

fun logRangeTimingRow(
    attributes: List<Int>,
    env: EvolutionEnvironment,
    label: String,
    treeMs: Double,
    evolutionMs: Double,
    scoringMs: Double,
    totalMs: Double
) {
    EvolutionContext.fullRangeTimingRows += RangeTimingRow(
        rule = attributes.joinToString(" + ") { idx ->
            env.columnNames[idx]
        },
        depth = attributes.size,
        searchKind = rangeSearchKindFromLabel(label),
        timings = mapOf(
            "total" to totalMs / 1000.0,
            "treeBuild" to treeMs / 1000.0,
            "evolution" to evolutionMs / 1000.0,
            "scoring" to scoringMs / 1000.0
        )
    )
}
