package org.jetbrains.bio.qfarm.output

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.bio.qfarm.util.HyperParameters
import org.jetbrains.bio.qfarm.columnNames
import java.io.BufferedWriter
import java.io.File
import java.time.Instant


/* --------------------------- JSON persistence --------------------------- */

@Serializable
data class RuleTreeRow(
    val type: String = "rule",
    val rule: List<String>,
    val addition: String,
    val depth: Int,
    val deltaArea: Double?,
    val totalArea: Double,

    val pValue: Double?,
    val pValueTwoSided: Double?,
    val zScore: Double?,
    val aucParent: Double?,
    val aucChild: Double?,
    val varianceParent: Double?,
    val varianceChild: Double?,
    val covariance: Double?,

    val label: String?,
    val frontUrl: String?,
    val createdAt: String
)

@Serializable
data class RHS(
    val rhs: String,
    val low: Double?,
    val high: Double?
)

@Serializable
data class RunMetadata(
    val type: String = "metadata",
    val rhs: RHS,
    val hyperparameters: HyperParameters,
    val timestamp: String,
)

@Serializable
data class RunSummary(
    val type: String = "summary",
    val runName: String,
    val totalRuntimeSeconds: Double,
    val finishedAt: String
)

class RuleTreeJsonWriter(
    file: File
) : AutoCloseable {

    private val writer: BufferedWriter = file.bufferedWriter()
    private val json = Json { encodeDefaults = true }

    fun append(
        prefix: List<Int>,
        addition: Int,
        depth: Int,
        deltaArea: Double?,
        totalArea: Double,
        pValue: Double?,
        pValueTwoSided: Double?,
        zScore: Double?,
        aucParent: Double?,
        aucChild: Double?,
        varianceParent: Double?,
        varianceChild: Double?,
        covariance: Double?,
        label: String?,
        frontUrl: String?,
        createdAt: Instant
    ) {
        val ruleNames =
            (prefix + addition)
                .distinct()
                .map { idx -> columnNames.getOrNull(idx) ?: "attr#$idx" }

        val row = RuleTreeRow(
            rule = ruleNames,
            addition = ruleNames.last(),
            depth = depth,
            deltaArea = deltaArea,
            totalArea = totalArea,

            pValue = pValue,
            pValueTwoSided = pValueTwoSided,
            zScore = zScore,
            aucParent = aucParent,
            aucChild = aucChild,
            varianceParent = varianceParent,
            varianceChild = varianceChild,
            covariance = covariance,

            label = label,
            frontUrl = frontUrl,
            createdAt = createdAt.toString()
        )

        writer.appendLine(json.encodeToString(row))
    }

    fun writeMetadata(rhs: RHS, hp: HyperParameters) {
        val meta = RunMetadata(
            rhs = rhs,
            hyperparameters = hp,
            timestamp = Instant.now().toString(),
        )
        writer.appendLine(json.encodeToString(meta))
        writer.flush()
    }

    fun writeSummary(runtimeSeconds: Double, runName: String) {
        val summary = RunSummary(
            runName = runName,
            totalRuntimeSeconds = runtimeSeconds,
            finishedAt = Instant.now().toString()
        )
        writer.appendLine(json.encodeToString(summary))
        writer.flush()
    }

    override fun close() {
        writer.flush()
        writer.close()
    }
}
