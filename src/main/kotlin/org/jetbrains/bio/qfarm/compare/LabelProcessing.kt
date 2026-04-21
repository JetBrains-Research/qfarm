package org.jetbrains.bio.qfarm.compare

private const val RAMP = " ▁▂▃▄▅▆▇█"

fun decodeBar(bar: String): IntArray {
    return bar
        .removePrefix("|")
        .removeSuffix("|")
        .map { ch ->
            val idx = RAMP.indexOf(ch)
            if (idx < 0) 0 else idx   // fallback safety
        }
        .toIntArray()
}

fun extractBarsFromLabel(label: String?): Map<String, IntArray> {
    if (label.isNullOrBlank()) return emptyMap()

    val lines = label.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val result = mutableMapOf<String, IntArray>()

    var i = 0
    while (i < lines.size) {
        if (i + 1 < lines.size && lines[i].endsWith(":")) {
            val attr = lines[i].removeSuffix(":").trim()
            val barLine = lines[i + 1].trim()

            result[attr] = decodeBar(barLine)

            i += 2
        } else {
            i++
        }
    }

    return result
}
