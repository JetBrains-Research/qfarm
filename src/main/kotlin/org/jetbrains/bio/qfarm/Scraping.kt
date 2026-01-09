package org.jetbrains.bio.qfarm

import org.jsoup.Jsoup
import org.apache.commons.text.StringEscapeUtils
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.max

/**
 * Reads a Lets-Plot HTML file and extracts plotSpec JSON.
 */
fun extractPlotSpecFromHtml(htmlFile: File): JsonObject {
    val outer = Jsoup.parse(htmlFile, "UTF-8")

    val iframe = outer.selectFirst("iframe")
        ?: error("No iframe found in HTML")

    val raw = iframe.attr("srcdoc")
    if (raw.isBlank()) error("iframe srcdoc is empty")

    // Double unescape (same as Python)
    val lvl1 = StringEscapeUtils.unescapeHtml4(raw)
    val lvl2 = StringEscapeUtils.unescapeHtml4(lvl1)

    val inner = Jsoup.parse(lvl2)
    val script = inner.selectFirst("script[data-lets-plot-script=plot]")
        ?: error("Lets-Plot script not found")

    val scriptText = script.data()

    val regex = Regex("const plotSpec = (\\{.*});", RegexOption.DOT_MATCHES_ALL)
    val match = regex.find(scriptText)
        ?: error("plotSpec JSON not found")

    val jsonText = match.groupValues[1].replace("undefined", "null")
    return Json.parseToJsonElement(jsonText).jsonObject
}

/**
 * Extracts rulePct strings for CHILD series only.
 */
fun extractChildRulePct(plotSpec: JsonObject): List<String> {
    val out = mutableListOf<String>()
    val layers = plotSpec["layers"]?.jsonArray ?: return out

    for (layer in layers) {
        val obj = layer.jsonObject
        if (obj["geom"]?.jsonPrimitive?.content != "point") continue

        val data = obj["data"]?.jsonObject ?: continue
        if (!data.containsKey("rulePct")) continue

        val series = data["series"]!!.jsonArray
        val rulePct = data["rulePct"]!!.jsonArray

        for (i in series.indices) {
            if (series[i].jsonPrimitive.content != "Parent") {
                out += rulePct[i].jsonPrimitive.content
            }
        }
    }
    return out
}

private val RULE_PCT_REGEX =
    Regex("""([^()\n]+?)\s*\(\s*(\d{1,3})\s*%\s*,\s*(\d{1,3})\s*%\s*\)""")

data class Interval(
    val attr: String,
    val lo: Int,
    val hi: Int
)

/** Parse rulePct strings that may contain MULTIPLE attributes */
fun parseIntervals(rulePcts: List<String>): List<Interval> =
    rulePcts.flatMap { text ->
        RULE_PCT_REGEX.findAll(text).map { m ->
            val attr = m.groupValues[1].trim()
            val lo = m.groupValues[2].toInt()
            val hi = m.groupValues[3].toInt()
            Interval(attr, lo, hi)
        }.toList()
    }


/** Unicode ramp */
private const val RAMP = " ▁▂▃▄▅▆▇█"

/** Map [0,1] → character */
private fun coverageChar(c: Double): Char {
    val idx = (c.coerceIn(0.0, 1.0) * (RAMP.length - 1)).roundToInt()
    return RAMP[idx]
}

/**
 * Build 20-bin boxed coverage bars per attribute.
 * Normalized to max (shape-based).
 */
fun buildCoverageBars(
    intervals: List<Interval>,
    bins: Int = 20
): Map<String, String> {

    val byAttr = intervals.groupBy { it.attr }
    val result = mutableMapOf<String, String>()

    for ((attr, ranges) in byAttr) {

        val coverages = DoubleArray(bins)

        for (r in ranges) {
            val lo = r.lo.toDouble()
            val hi = r.hi.toDouble()
            if (hi <= lo) continue

            val width = hi - lo
            val nSamples = max(20, width.roundToInt())

            for (k in 0 until nSamples) {
                val x = lo + (hi - lo) * k / (nSamples - 1)
                val bin = ((x / 100.0) * bins)
                    .toInt()
                    .coerceIn(0, bins - 1)
                coverages[bin] += 1.0
            }
        }

        val maxCov = coverages.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val bar = buildString {
            for (i in 0 until bins) {
                append(coverageChar(coverages[i] / maxCov))
            }
        }

        result[attr] = "|$bar|"
    }

    return result
}

fun buildBarsFromHtml(htmlFile: File): Map<String, String> {
    val plotSpec = extractPlotSpecFromHtml(htmlFile)
    val rulePcts = extractChildRulePct(plotSpec)
    val intervals = parseIntervals(rulePcts)
    return buildCoverageBars(intervals)
}
