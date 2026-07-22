package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class RootCommand : CliktCommand(name = "qfarm") {

    override fun commandHelp(context: Context): String = """
        QFARM mines quantitative association rules from tabular datasets
        and validates previously generated rule trees.

        Select one of the commands below to continue.
    """.trimIndent()

    override fun commandHelpEpilog(context: Context): String = """
        Examples:

          Search for rules using a percentile-based RHS range:
            java -jar qfarm.jar search \
              --data data.csv \
              --rhs y \
              --rhs-range-percentile 90,100

          Search for rules using an absolute RHS range:
            java -jar qfarm.jar search \
              --data data.csv \
              --rhs y \
              --rhs-range 4.0,MAX

          Validate rules on another dataset:
            java -jar qfarm.jar validate \
              --data validation.csv \
              --rules results/test_run/log.jsonl

        Run a command with --help for command-specific options:

          java -jar qfarm.jar search --help
          java -jar qfarm.jar validate --help
    """.trimIndent()

    override fun run() = Unit
}
