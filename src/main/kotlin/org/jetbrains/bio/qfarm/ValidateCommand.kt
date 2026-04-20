package org.jetbrains.bio.qfarm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*

class ValidateCommand : CliktCommand(name = "validate") {

    // ===== REQUIRED =====
    private val dataPath by option(
        "--data",
        help = "path/to dataset.csv"
    ).required()

    // ===== OPTIONAL (future-proof) =====
    private val modelPath by option(
        "--model",
        help = "optional model or rules file to validate"
    )

    private val outputPath by option(
        "--output",
        help = "optional output file for validation results"
    )

    override fun run() {

        echo("Running validation on dataset: $dataPath")

        // Placeholder for now
        runValidation(
            dataPath = dataPath,
            modelPath = modelPath,
            outputPath = outputPath
        )
    }

    private fun runValidation(
        dataPath: String,
        modelPath: String?,
        outputPath: String?
    ) {
        // TODO: plug your real validation logic here

        echo("Validation logic not implemented yet.")
        echo("data=$dataPath, model=$modelPath, output=$outputPath")
    }
}
