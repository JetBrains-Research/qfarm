package org.jetbrains.bio.qfarm.visualization

import org.jetbrains.bio.qfarm.PLOTS_DIR
import org.jetbrains.bio.qfarm.columnNames
import org.jetbrains.bio.qfarm.util.RESET
import org.jetbrains.bio.qfarm.util.YELLOW
import org.jetbrains.bio.qfarm.util.hp
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.intern.Plot
import java.io.File

val plotDir = "front_plots_${hp.runName}"
val plots_file_path = "${PLOTS_DIR}/$plotDir"

const val PLOT_WIDTH = 800
const val PLOT_HEIGHT = 650

object FrontStore {
    private val dir = File(plots_file_path).apply { mkdirs() }

    private fun safeName(s: String) = s.replace(Regex("""[^\w\-.]+"""), "_").take(120)

    /** Saves plot as HTML and returns file:// URL, or null on failure. */
    fun saveAndUrl(plot: Plot, titleHint: String): String? = try {
        val base = safeName(titleHint)
        val out = File(dir, "$base.html")
        ggsave(plot, path = out.parent, filename = out.name)
        out.toURI().toString()
    } catch (t: Throwable) {
        println("${YELLOW}[⚠️ Failed to save front plot: ${t.message}]${RESET}"); null
    }
}

fun buildPalette(seriesNames: List<String>): Pair<List<String>, List<String>> {
    val palette = when (seriesNames.size) {
        0 -> emptyList()
        1 -> listOf("#1f77b4")
        2 -> listOf("#1f77b4", "#ff7f0e")
        else -> (0 until seriesNames.size).map { i ->
            "hsl(${(360.0 / seriesNames.size * i).toInt()},70%,50%)"
        }
    }
    return seriesNames to palette.take(seriesNames.size)
}

fun attrsToFileName(attrs: List<Int>): String {
    return attrs.joinToString("_AND_") { idx ->
        val raw = columnNames.getOrNull(idx) ?: "X$idx"

        // sanitize for filesystem
        raw
            .trim()
            .replace("\\s+".toRegex(), "_")        // spaces → _
            .replace("[^a-zA-Z0-9._-]".toRegex(), "") // remove weird chars
    }
}

fun saveCombinedHtmlHorizontal(
    pfUrl: String,
    rocUrl: String,
    filename: String
): String? {
    return try {
        val out = File("${plots_file_path}/$filename.html")

        val html = """
            <html>
            <head>
                <title>$filename</title>
            </head>
            <body style="font-family: sans-serif; margin:0; padding:0;">
                
                <div style="display:flex; width:100%; height:100vh;">
                    
                    <div style="flex:1; padding:10px;">
                        <h3 style="text-align:center;">Pareto Front</h3>
                        <iframe src="$pfUrl" width="100%" height="90%" style="border:none;"></iframe>
                    </div>
                    
                    <div style="flex:1; padding:10px;">
                        <h3 style="text-align:center;">ROC Curves</h3>
                        <iframe src="$rocUrl" width="100%" height="90%" style="border:none;"></iframe>
                    </div>
                
                </div>
                
            </body>
            </html>
        """.trimIndent()

        out.writeText(html)
        out.toURI().toString()

    } catch (t: Throwable) {
        println("${YELLOW}[⚠️ Failed to save combined HTML: ${t.message}]${RESET}")
        null
    }
}
