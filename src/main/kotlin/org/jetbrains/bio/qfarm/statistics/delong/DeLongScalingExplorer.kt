package org.jetbrains.bio.qfarm.statistics.delong

import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import kotlin.system.measureNanoTime
import kotlin.random.Random

object DeLongScalingExplorer {

    @JvmStatic
    fun main(args: Array<String>) {
        runScaling()
    }

    fun runScaling() {
        val sizes = listOf(
            100,
            500,
            1_000,
            5_000,
            10_000,
            25_000,
            50_000,
            75_000,
            100_000
        )

        val results = mutableListOf<Pair<Int, Double>>()

        for (size in sizes) {

            val random = Random(42)
            val labels = IntArray(size) { if (random.nextDouble() > 0.5) 1 else 0 }
            val model1 = DoubleArray(size) { random.nextDouble() }
            val model2 = DoubleArray(size) { random.nextDouble() }

            repeat(30) {
                DeLong.compare(labels, model1, model2)
            }

            val repetitions = 20

            val totalNanos = measureNanoTime {
                repeat(repetitions) {
                    DeLong.compare(labels, model1, model2)
                }
            }

            val avgMs = totalNanos / 1_000_000.0 / repetitions
            results += size to avgMs

            println("Size=$size  Avg=$avgMs ms")
        }

        plotScaling(results)
    }

    private fun plotScaling(data: List<Pair<Int, Double>>) {
        val sizes = data.map { it.first }
        val times = data.map { it.second }

        val plot = letsPlot(mapOf(
            "size" to sizes,
            "time" to times
        )) +
                geomLine {
                    x = "size"
                    y = "time"
                } +
                geomPoint {
                    x = "size"
                    y = "time"
                } +
                ggtitle("DeLong Runtime Scaling") +
                xlab("Dataset Size") +
                ylab("Average Runtime (ms)")

        // Saves into build folder
        val outputDir = java.io.File("build/reports")
        outputDir.mkdirs()

        val outputFile = java.io.File(outputDir, "delong_scaling.png")

        ggsave(plot, outputFile.absolutePath)

        println("Plot saved to ${outputFile.absolutePath}")

        println("Plot saved to build/reports/delong_scaling.png")
    }
}
