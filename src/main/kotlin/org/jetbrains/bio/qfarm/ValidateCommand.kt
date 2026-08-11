package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.validate.loadRulesJson

class ValidateCommand : CliktCommand(name = "validate") {

    override fun commandHelp(context: Context): String = """
        Validate a previously generated QFARM rule tree on a dataset.

        Rule metadata and the original run hyperparameters are restored from
        the supplied JSONL log. Validation-specific options may override
        selected values.

        The Spearman threshold is not restored from the JSONL log. If it is
        not provided through the CLI, the default value from HyperParameters
        is used.
    """.trimIndent()

    override fun commandHelpEpilog(context: Context): String = """
        Example:

          java -jar qfarm.jar validate \
            --data data.csv \
            --rules results/previous_run/log.jsonl \
            --seed 42 \
            --min-support 5 \
            --max-support 500 \
            --spearman-threshold 0.20

        The validation results are written to the run-specific results
        directory, including validation_summary.txt and the generated
        rule-tree outputs.
    """.trimIndent()

    private val dataPath by option(
        "--data",
        metavar = "PATH",
        help = "Path to the CSV dataset on which the rules will be validated."
    ).required()

    private val rulesPath by option(
        "--rules",
        metavar = "PATH",
        help = """
            Path to the QFARM JSONL log containing the previously generated
            rules and run metadata, normally results/<run-name>/log.jsonl.
        """.trimIndent()
    ).required()

    private val seedOpt by option(
        "--seed",
        metavar = "LONG",
        help = """
        Seed controlling all random number generation.
        Default: ${hp.seed}.
    """.trimIndent()
    ).long()

    private val minSupportOpt by option(
        "--min-support",
        metavar = "COUNT",
        help = """
            Override the minimum rule support stored in the JSONL metadata.
            When omitted, the stored value is used.
        """.trimIndent()
    ).int()

    private val maxSupportOpt by option(
        "--max-support",
        metavar = "COUNT",
        help = """
            Override the maximum rule support stored in the JSONL metadata.
            When omitted, the stored value is used.
        """.trimIndent()
    ).int()

    private val spearmanThresholdOpt by option(
        "--spearman-threshold",
        metavar = "VALUE",
        help = """
            Smoothed Spearman-distance threshold used during the final
            comparison with rules from the previous run. Rules exceeding
            this threshold are filtered from the final validation results.
            Default: ${hp.spearmanThreshold}.
        """.trimIndent()
    ).double()

    override fun run() {
        echo("Running validation")
        echo("Dataset: $dataPath")
        echo("Rules: $rulesPath")

        val loaded = loadRulesJson(rulesPath)
        val metadata = loaded.metadata

        val rhsName = metadata.rhs.rhs
        val rhsRange = metadata.rhs.low to metadata.rhs.high

        val hpFromJson = metadata.hyperparameters

        // Spearman threshold is not stored in the JSON metadata.
        // Preserve the current default before replacing the global hp object.
        val defaultSpearmanThreshold = hp.spearmanThreshold

        hp = hpFromJson.copy(
            dataPath = dataPath,
            rightAttribute = rhsName,
            seed = seedOpt ?: hpFromJson.seed,
            minSupport = minSupportOpt ?: hpFromJson.minSupport,
            maxSupport = maxSupportOpt ?: hpFromJson.maxSupport,
            spearmanThreshold =
                spearmanThresholdOpt ?: defaultSpearmanThreshold
        )

        initEnvironment(
            dataPath = dataPath,
            rhsName = rhsName,
            rhsRange = rhsRange
        )

        runValidation(loaded)
    }
}
