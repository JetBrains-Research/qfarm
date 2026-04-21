package org.jetbrains.bio.qfarm.util.validate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.bio.qfarm.output.logs.RuleTreeRow
import org.jetbrains.bio.qfarm.output.logs.RunMetadata
import java.io.File

data class LoadedRulesFile(
    val metadata: RunMetadata,
    val rules: List<RuleTreeRow>
)

fun loadRulesJson(path: String): LoadedRulesFile {

    val file = File(path)
    require(file.exists()) { "Rules file not found: $path" }

    val json = Json { ignoreUnknownKeys = true }

    var metadata: RunMetadata? = null
    val rules = mutableListOf<RuleTreeRow>()

    file.useLines { lines ->
        lines.forEachIndexed { _, line ->

            if (line.isBlank()) return@forEachIndexed

            val obj = json.parseToJsonElement(line).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content

            when (type) {

                "metadata" -> {
                    metadata = json.decodeFromJsonElement<RunMetadata>(obj)
                }

                "rule" -> {
                    val row = json.decodeFromJsonElement<RuleTreeRow>(obj)
                    rules += row
                }

                else -> {
                    // ignore summary or unknown
                }
            }
        }
    }

    require(metadata != null) {
        "No metadata found in JSONL file"
    }
    // kotlin complaining...
    val meta = metadata!!

    require(rules.isNotEmpty()) {
        "No rules found in JSONL file"
    }

    // Ensure stable order (important for replay)
    val sortedRules = rules.sortedWith(
        compareBy<RuleTreeRow> { it.depth }
            .thenBy { it.createdAt }
    )

    return LoadedRulesFile(
        metadata = meta,
        rules = sortedRules
    )
}
