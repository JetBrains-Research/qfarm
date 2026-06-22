package org.jetbrains.bio.qfarm

import org.jetbrains.bio.qfarm.evaluation.random.generateAnalyticalRandomAucBaseline
import org.jetbrains.bio.qfarm.evolution.search.treeTraversal
import org.jetbrains.bio.qfarm.output.OutputManager
import org.jetbrains.bio.qfarm.output.logs.RHS
import org.jetbrains.bio.qfarm.output.tree.RULE_TREE_ROOT
import org.jetbrains.bio.qfarm.output.logs.RuleTreeJsonWriter
import org.jetbrains.bio.qfarm.output.fronts.exportAllRuleFormats
import org.jetbrains.bio.qfarm.output.tree.exportLeafRules
import org.jetbrains.bio.qfarm.output.tree.toDOTFromTrie
import org.jetbrains.bio.qfarm.params.BLUE
import org.jetbrains.bio.qfarm.params.RESET
import org.jetbrains.bio.qfarm.params.hp
import java.io.File


fun runSearch() {
    val start = System.nanoTime()

    val timestamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

    OUTPUT = OutputManager(
        baseDir = File("results"),
        runName = "${hp.runName}_$timestamp"
    )
    OUTPUT.init()

    generateAnalyticalRandomAucBaseline(nShuffles = hp.randomAucBaselineColumns)

    RULE_JSON_WRITER = RuleTreeJsonWriter(OUTPUT.logFile)

    RULE_JSON_WRITER.writeMetadata(
        rhs = RHS(columnNames[rightAttrIndex], rightGene.lowerBound, rightGene.upperBound),
        hp
    )

    val emptyPrefix: MutableList<Int> = mutableListOf()
    treeTraversal(emptyPrefix)

    exportLeafRules(
        RULE_TREE_ROOT,
        datasetWithHeader
    )

    TOPRULES.forEach { rule ->
        println()
        rule.forEach { idx ->
            print(
                " + ${BLUE}${columnNames[idx]}${RESET}"
            )
        }
    }

    val dot = toDOTFromTrie(RULE_TREE_ROOT, header = datasetWithHeader.header)
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

    val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
    println("\nTOTAL RUNTIME: $elapsed s")

    RULE_JSON_WRITER.writeSummary(
        runtimeSeconds = elapsed,
        runName = hp.runName
    )

    RULE_JSON_WRITER.close()

}
