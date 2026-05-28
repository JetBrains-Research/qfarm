package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.validate.loadRulesJson

class ValidateCommand : CliktCommand(name = "validate") {

    // ===== REQUIRED =====
    private val dataPath by option(
        "--data",
        help = "path/to dataset.csv"
    ).required()

    private val rulesPath by option(
        "--rules",
        help = "path to rules file (.jsonl)"
    ).required()

    private val minSupportOpt by option("--min-support").int()
    private val maxSupportOpt by option("--max-support").int()

    private val spearmanThresholdOpt by option("--spearman-threshold").double()

    override fun run() {

        echo("Running validation")
        echo("Dataset: $dataPath")
        echo("Rules: $rulesPath")

        // 1. Load JSON rules
        val loaded = loadRulesJson(rulesPath)

        val metadata = loaded.metadata

        val rhsName = metadata.rhs.rhs
        val rhsRange = metadata.rhs.low to metadata.rhs.high

        // 2. Restore hyperparameters from json
        val hpFromJson = metadata.hyperparameters

        hp = hpFromJson.copy(
            dataPath = dataPath,
            rightAttribute = rhsName,
            minSupport = minSupportOpt ?: hpFromJson.minSupport,
            maxSupport = maxSupportOpt ?: hpFromJson.maxSupport,
            spearmanThreshold = spearmanThresholdOpt ?: hpFromJson.spearmanThreshold
        )

        // 3. Init environment (NEW dataset, SAME RHS)
        initEnvironment(
            dataPath = dataPath,
            rhsName = rhsName,
            rhsRange = rhsRange
        )

        // 4. Execute validation
        runValidation(loaded)
    }
}
