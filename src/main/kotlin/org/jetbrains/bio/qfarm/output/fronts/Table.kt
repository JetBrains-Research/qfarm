package org.jetbrains.bio.qfarm.output.fronts

import java.io.File

fun writeCsv(rows: List<ExportRuleRow>, file: File) {

    fun escapeCsv(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    file.bufferedWriter().use { w ->

        // 🔹 New header (clean + ordered)
        w.appendLine("nID,pnID,rule,p_value,auc,area,plots")

        for (r in rows) {

            val plots = flattenLabel(r.label)

            w.appendLine(
                listOf(
                    r.id,
                    r.parentId ?: "",
                    escapeCsv(r.rule),
                    r.pValue ?: "",
                    r.auc ?: "",
                    r.area ?: "",
                    escapeCsv(plots)
                ).joinToString(",")
            )
        }
    }
}
