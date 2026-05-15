package org.jetbrains.bio.qfarm.output.tree

import java.io.File

object NodeLabeler {

    fun buildLabel(node: RuleTreeNode): String {
        if (node.additionAttrIndex == null) return "START"

        val htmlFile = resolveFrontHtml(node)
        if (htmlFile == null) {
            println("[NodeLabeler] No PF HTML for node ${node.additionAttrIndex}")
            return ""
        }

        println("[NodeLabeler] Parsing label from: ${htmlFile.absolutePath}")

        val bars = try {
            buildBarsFromHtml(htmlFile)
        } catch (e: Exception) {
            println("[NodeLabeler] Failed to parse bars from $htmlFile: $e")
            emptyMap()
        }

        if (bars.isEmpty()) {
            println("[NodeLabeler] No bars found in: ${htmlFile.name}")
            return ""
        }

        return bars.entries.joinToString("\n") { (attr, bar) ->
            "${attr}:\n$bar"
        }
    }

    private fun resolveFrontHtml(n: RuleTreeNode): File? {
        val url = n.plots?.pfUrl ?: return null

        return try {
            val file = File(java.net.URI(url))
            if (!file.exists()) {
                println("[NodeLabeler] PF file does not exist: ${file.absolutePath}")
                return null
            }
            file
        } catch (e: Exception) {
            println("[NodeLabeler] Failed to resolve Pareto HTML URI: $url | $e")
            null
        }
    }
}
