package org.jetbrains.bio.qfarm.statistics.delong

import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable


@Serializable
data class GoldenCase(
    val metadata: Metadata,
    val data: DataBlock,
    val expected: Expected
)

@Serializable
data class Metadata(
    val delong_library: String,
    val delong_version: String,
    val numpy_version: String
)

@Serializable
data class DataBlock(
    val labels: List<Int>,
    val scores1: List<Double>,
    val scores2: List<Double>
)

@Serializable
data class Expected(
    val auc1: Double,
    val auc2: Double,
    val delta_auc: Double,
    val z: Double,
    val p_two_sided: Double,
    val p_one_sided: Double
)

class DeLongFixedTest {

    fun Double.sci(): String = "% .6e".format(this)

    fun relError(expected: Double, actual: Double): Double {
        val denom = maxOf(
            kotlin.math.abs(expected),
            kotlin.math.abs(actual),
            1e-15
        )
        return kotlin.math.abs(actual - expected) / denom
    }

    fun diff(expected: Double, actual: Double): String {
        val absDiff = actual - expected
        return "Δ=${absDiff.sci()} | rel=${relError(expected, actual).sci()}"
    }

    companion object {

        @JvmStatic
        fun cases(): List<String> {
            val basePath = "/delong"
            val baseUrl = DeLongFixedTest::class.java.getResource(basePath)
                ?: error("Folder $basePath not found")

            val baseDir = Paths.get(baseUrl.toURI())

            return Files.list(baseDir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .map { "$basePath/${it.fileName}" }
                .toList()
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun shouldMatchGoldenReference(resourcePath: String) {

        val stream = this::class.java.getResourceAsStream(resourcePath)
            ?: error("File not found: $resourcePath")

        val jsonText = stream.bufferedReader().readText()

        val json = Json {
            ignoreUnknownKeys = true
        }

        val golden = json.decodeFromString<GoldenCase>(jsonText)

        val result = DeLong.compare(
            golden.data.labels.toIntArray(),
            golden.data.scores1.toDoubleArray(),
            golden.data.scores2.toDoubleArray()
        )

        // -------- PRINT BLOCK --------

        println("--------------------------------------------------")
        println("Running golden test: $resourcePath")

        println("AUC1   -> exp: ${golden.expected.auc1.sci()} | act: ${result.auc1.sci()} | ${diff(golden.expected.auc1, result.auc1)}")

        println("AUC2   -> exp: ${golden.expected.auc2.sci()} | act: ${result.auc2.sci()} | ${diff(golden.expected.auc2, result.auc2)}")

        val expectedDelta = golden.expected.delta_auc
        val actualDelta = result.auc2 - result.auc1

        println("ΔAUC   -> exp: ${expectedDelta.sci()} | act: ${actualDelta.sci()} | ${diff(expectedDelta, actualDelta)}")

        println("z      -> exp: ${golden.expected.z.sci()} | act: ${result.zScore.sci()} | ${diff(golden.expected.z, result.zScore)}")

        println("p(two) -> exp: ${golden.expected.p_two_sided.sci()} | act: ${result.pTwoSided.sci()} | ${diff(golden.expected.p_two_sided, result.pTwoSided)}")

        println("p(one) -> exp: ${golden.expected.p_one_sided.sci()} | act: ${result.pOneSided.sci()} | ${diff(golden.expected.p_one_sided, result.pOneSided)}")

        println("--------------------------------------------------")


        assertEquals(golden.expected.auc1, result.auc1, 1e-10)
        assertEquals(golden.expected.auc2, result.auc2, 1e-10)
        assertEquals(golden.expected.z, result.zScore, 1e-6)

        // TODO: remove fixed tolerance, bcz depends on the scale of pvalue.
        //  For example, if pval is huge (2.5) the error can be more than 1e-12 simply bcz of computation float error
        //  If on the other hand, pval small (1e-30), error will be of scale 1e-80 or something...
        if (golden.expected.p_two_sided == 0.0) {
            assertEquals(0.0, result.pTwoSided)
        } else {
            assertEquals(golden.expected.p_two_sided, result.pTwoSided, 1e-12)
        }

        if (golden.expected.p_one_sided == 0.0) {
            assertEquals(0.0, result.pOneSided)
        } else {
            assertEquals(golden.expected.p_one_sided, result.pOneSided, 1e-12)
        }
    }

}
