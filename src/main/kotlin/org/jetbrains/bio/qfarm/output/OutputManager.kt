package org.jetbrains.bio.qfarm.output

import java.io.File

data class OutputManager(
    val baseDir: File,
    val runName: String
) {
    val safeRunName = sanitize(runName)
    val runDir = File(baseDir, safeRunName)

    val frontPlotsDir = File(runDir, "front_plots")

    val logFile = File(runDir, "log.jsonl")
    val reprRulesFile = File(runDir, "representative_rules_table.tsv")
    val rulesTreeTxt = File(runDir, "final_rules_summary.txt")
    val rulesTreeCsv = File(runDir, "final_rules_table.csv")

    val treeDot = File(runDir, "full_tree.dot")
    val treeSvg = File(runDir, "full_tree.svg")

    val rulesTreeValidatedTxt = File(runDir, "validation_summary.txt")

    val rangeTimingsCsv = File(runDir, "range_timings.csv")

    fun init() {
        require(baseDir.exists() || baseDir.mkdirs()) {
            "Failed to create base directory: $baseDir"
        }

        runDir.mkdirs()
        frontPlotsDir.mkdirs()
    }
}

fun sanitize(name: String): String =
    name.replace(Regex("[^a-zA-Z0-9._/\\\\-]"), "_")
