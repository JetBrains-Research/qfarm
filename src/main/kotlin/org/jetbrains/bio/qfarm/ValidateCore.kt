package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.evolution.validate.reevaluateTree
import org.jetbrains.bio.qfarm.output.OutputManager
import org.jetbrains.bio.qfarm.output.fronts.buildExportRows
import org.jetbrains.bio.qfarm.output.fronts.exportAllRuleFormats
import org.jetbrains.bio.qfarm.output.logs.RuleTreeJsonWriter
import org.jetbrains.bio.qfarm.output.tree.RULE_TREE_ROOT
import org.jetbrains.bio.qfarm.output.tree.exportLeafRules
import org.jetbrains.bio.qfarm.output.tree.toDOTFromTrie
import org.jetbrains.bio.qfarm.output.validate.writeTxtValidated
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.validate.LoadedRulesFile
import java.io.File

fun runValidation(loaded: LoadedRulesFile) {

    val start = System.nanoTime()

    // Output setup
    val timestamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

    OUTPUT = OutputManager(
        baseDir = File("results"),
        runName = "validation_${hp.runName}_$timestamp"
    )
    OUTPUT.init()

    RULE_JSON_WRITER = RuleTreeJsonWriter(OUTPUT.logFile)

    RULE_JSON_WRITER.writeMetadata(
        rhs = loaded.metadata.rhs,
        hp = loaded.metadata.hyperparameters
    )

    // Reset runtime state
    RULE_TREE_ROOT.children.clear()
    RULE_TREE_ROOT.steps.clear()

    // Reevaluate rules on the new dataset
    reevaluateTree(loaded.rules)

    // Export (IDENTICAL to search)
    exportLeafRules(
        RULE_TREE_ROOT,
        datasetWithHeader
    )

    val dot = toDOTFromTrie(
        RULE_TREE_ROOT,
        header = datasetWithHeader.header
    )

    OUTPUT.treeDot.writeText(dot)

    ProcessBuilder(
        "dot",
        "-Tsvg",
        OUTPUT.treeDot.absolutePath,
        "-o",
        OUTPUT.treeSvg.absolutePath
    )
        .redirectErrorStream(true)
        .start()
        .waitFor()

    exportAllRuleFormats(RULE_TREE_ROOT)

    val (rows, idToNode) = buildExportRows(
        RULE_TREE_ROOT,
        columnNames
    )

    writeTxtValidated(
        rows = rows,
        idToNode = idToNode,
        originalRows = loaded.rules,
        datasetName = File(hp.dataPath ?: "").name,
        previousRunName = loaded.metadata.hyperparameters.runName,
        file = OUTPUT.rulesTreeValidatedTxt
    )

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("\nVALIDATION RUNTIME: $elapsed s")

    RULE_JSON_WRITER.writeSummary(
        runtimeSeconds = elapsed,
        runName = hp.runName
    )

    RULE_JSON_WRITER.close()
}
