package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*

class ValidateCommand : CliktCommand(name = "validate") {

    // ===== REQUIRED =====
    private val dataPath by option(
        "--data",
        help = "path/to dataset.csv"
    ).required()

    private val rulesPath by option(
        "--rules",
        help = "path to rules file (.txt)"
    ).required()

    override fun run() {

        echo("Running validation")
        echo("Dataset: $dataPath")
        echo("Rules: $rulesPath")

        runValidation(
            dataPath = dataPath,
            rulesPath = rulesPath
        )
    }

    private fun runValidation(
        dataPath: String,
        rulesPath: String
    ) {
        // TODO: implement real validation logic

        echo("Validation not implemented yet for $dataPath and $rulesPath")
    }
}
