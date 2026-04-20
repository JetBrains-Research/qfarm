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
    val leafRulesFile = File(runDir, "leaf_rules.tsv")
    val rulesTreeTxt = File(runDir, "rules_tree_light.txt")
    val rulesTreeCsv = File(runDir, "rules_tree.csv")

    val treeDot = File(runDir, "tree.dot")
    val treeSvg = File(runDir, "tree.svg")

    fun init() {
        require(baseDir.exists() || baseDir.mkdirs()) {
            "Failed to create base directory: $baseDir"
        }

        runDir.mkdirs()
        frontPlotsDir.mkdirs()
    }
}

fun sanitize(name: String): String =
    name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
