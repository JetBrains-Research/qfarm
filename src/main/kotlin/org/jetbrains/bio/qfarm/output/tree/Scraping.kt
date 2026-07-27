package org.jetbrains.bio.qfarm.output.tree

import org.jsoup.Jsoup
import org.apache.commons.text.StringEscapeUtils
import kotlinx.serialization.json.*
import java.io.File
import kotlin.collections.iterator
import kotlin.math.roundToInt
import kotlin.math.max


private const val CONFIDENCE_FIELD = "Confidence"

data class WeightedRulePct(
    val rulePct: String,
    val confidence: Double
)

data class WeightedInterval(
    val attr: String,
    val lo: Int,
    val hi: Int,
    val confidence: Double
)

/**
 * Reads a Lets-Plot HTML file and extracts plotSpec JSON.
 */
fun extractPlotSpecFromHtml(htmlFile: File): JsonObject {
    val pfDoc = Jsoup.parse(htmlFile, "UTF-8")

    val innerIframe = pfDoc.selectFirst("iframe")
        ?: error("Inner iframe not found in PF file: ${htmlFile.absolutePath}")

    val raw = innerIframe.attr("srcdoc")
    if (raw.isBlank()) {
        error("Inner srcdoc is empty in PF file: ${htmlFile.absolutePath}")
    }

    val lvl1 = StringEscapeUtils.unescapeHtml4(raw)
    val lvl2 = StringEscapeUtils.unescapeHtml4(lvl1)

    val inner = Jsoup.parse(lvl2)

    val script = inner.selectFirst("script[data-lets-plot-script=plot]")
        ?: error("Lets-Plot script not found in PF file: ${htmlFile.absolutePath}")

    val scriptText = script.data()

    val regex = Regex(
        """const plotSpec = (\{.*});""",
        RegexOption.DOT_MATCHES_ALL
    )

    val match = regex.find(scriptText)
        ?: error("plotSpec JSON not found in PF file: ${htmlFile.absolutePath}")

    val jsonText = match.groupValues[1]
        .replace("undefined", "null")

    return Json.parseToJsonElement(jsonText).jsonObject
}

/**
 * Extracts CHILD rule descriptions together with their confidence.
 *
 * Assumes rulePct, series, and confidence are parallel arrays
 * inside the same Lets-Plot layer.
 */
fun extractChildRulePcts(
    plotSpec: JsonObject
): List<WeightedRulePct> {

    val out = mutableListOf<WeightedRulePct>()
    val layers = plotSpec["layers"]?.jsonArray ?: return out

    for (layer in layers) {
        val obj = layer.jsonObject
        val data = obj["data"]?.jsonObject ?: continue

        val series = data["series"]?.jsonArray ?: continue
        val rulePcts = data["rulePct"]?.jsonArray ?: continue
        val confidences = data[CONFIDENCE_FIELD]?.jsonArray ?: continue

        val size = minOf(
            series.size,
            rulePcts.size,
            confidences.size
        )

        for (i in 0 until size) {
            if (series[i].jsonPrimitive.content != "Child") {
                continue
            }

            val rulePct = rulePcts[i].jsonPrimitive.content

            val confidence = confidences[i]
                .jsonPrimitive
                .double

            out += WeightedRulePct(
                rulePct = rulePct,
                confidence = confidence
            )
        }
    }

    return out
}

private val RULE_PCT_REGEX =
    Regex("""([^()\n]+?)\s*\(\s*(\d{1,3})\s*%\s*,\s*(\d{1,3})\s*%\s*\)""")

/**
 * Parses rulePct strings that may contain multiple attributes.
 *
 * Every interval originating from the same rule receives that
 * rule's confidence.
 */
fun parseWeightedIntervals(
    rules: List<WeightedRulePct>
): List<WeightedInterval> =
    rules.flatMap { rule ->
        RULE_PCT_REGEX
            .findAll(rule.rulePct.trim())
            .map { match ->
                WeightedInterval(
                    attr = match.groupValues[1].trim(),
                    lo = match.groupValues[2].toInt(),
                    hi = match.groupValues[3].toInt(),
                    confidence = rule.confidence
                )
            }
            .toList()
    }


/** Unicode ramp */
private const val RAMP = " ▁▂▃▄▅▆▇█"

/** Map [0,1] → character */
private fun coverageChar(c: Double): Char {
    val idx = (c.coerceIn(0.0, 1.0) * (RAMP.length - 1)).roundToInt()
    return RAMP[idx]
}

/**
 * Builds confidence-weighted coverage bars per attribute.
 *
 * A rule contributes its confidence to every bin that its interval covers.
 * The final bar is normalized by the largest weighted bin value so that
 * the output still visualizes the relative shape.
 */
fun buildCoverageBars(
    intervals: List<WeightedInterval>,
    bins: Int = 20
): Map<String, String> {

    val byAttr = intervals.groupBy { it.attr }
    val result = linkedMapOf<String, String>()

    for ((attr, ranges) in byAttr) {
        val coverages = DoubleArray(bins)

        for (range in ranges) {
            val lo = range.lo.toDouble()
            val hi = range.hi.toDouble()

            if (hi <= lo) continue

            val confidence = range.confidence.coerceAtLeast(0.0)

            val width = hi - lo
            val nSamples = max(20, width.roundToInt())

            for (k in 0 until nSamples) {
                val x = if (nSamples == 1) {
                    lo
                } else {
                    lo + width * k / (nSamples - 1)
                }

                val bin = ((x / 100.0) * bins)
                    .toInt()
                    .coerceIn(0, bins - 1)

                coverages[bin] += confidence
            }
        }

        val maxCoverage =
            coverages.maxOrNull()
                ?.takeIf { it > 0.0 }
                ?: 1.0

        val bar = buildString {
            for (coverage in coverages) {
                append(
                    coverageChar(
                        coverage / maxCoverage
                    )
                )
            }
        }

        result[attr] = "|$bar|"
    }

    return result
}

fun buildBarsFromHtml(
    htmlFile: File
): Map<String, String> {

    val plotSpec = extractPlotSpecFromHtml(htmlFile)
    val rules = extractChildRulePcts(plotSpec)
    val intervals = parseWeightedIntervals(rules)

    return buildCoverageBars(intervals)
}
