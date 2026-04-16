package org.jetbrains.bio.qfarm.output.fronts

fun formatP(v: Double?) = v?.let { "%.2e".format(it) } ?: ""
fun formatAuc(v: Double?) = v?.let { "%.4f".format(it) } ?: ""
fun formatArea(v: Double?) = v?.let { "%.4f".format(it) } ?: ""

fun pad(value: String, width: Int) = value.padEnd(width)

fun flattenLabel(label: String?): String {
    if (label.isNullOrBlank()) return ""

    val lines = label.lines().map { it.trim() }.filter { it.isNotEmpty() }

    val parts = mutableListOf<String>()
    var i = 0

    while (i < lines.size) {
        if (i + 1 < lines.size && lines[i].endsWith(":")) {

            val attr = lines[i].removeSuffix(":")   // "X1"
            val bar = lines[i + 1].trim()           // "| ▁▄▅|"

            parts += "$attr:$bar"

            i += 2
        } else {
            parts += lines[i]
            i++
        }
    }

    return parts.joinToString("  AND  ")
}

fun formatNumber(x: Double): String {
    return if (x % 1.0 == 0.0) {
        x.toInt().toString()
    } else {
        "%.4f".format(x)
    }
}
