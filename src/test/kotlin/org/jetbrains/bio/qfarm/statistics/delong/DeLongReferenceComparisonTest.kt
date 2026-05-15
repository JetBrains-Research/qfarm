package org.jetbrains.bio.qfarm.statistics.delong

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.random.Random
import java.io.OutputStreamWriter
import java.io.BufferedWriter
import org.json.JSONObject
import kotlin.math.abs

class DeLongReferenceComparisonTest {

    @Test
    fun compareAgainstPythonReference() {

        val size = 5000
        val random = Random(42)

        val labels = IntArray(size) { if (random.nextDouble() > 0.5) 1 else 0 }
        val scores1 = DoubleArray(size) { random.nextDouble() }
        val scores2 = DoubleArray(size) { random.nextDouble() }

        val kotlinResult = DeLong.compare(labels, scores1, scores2)

        val inputJson = JSONObject().apply {
            put("labels", labels.toList())
            put("scores1", scores1.toList())
            put("scores2", scores2.toList())
        }

        val process = ProcessBuilder(".venv/bin/python", "delong_reference.py")
            .redirectErrorStream(true)
            .start()

        BufferedWriter(OutputStreamWriter(process.outputStream)).use {
            it.write(inputJson.toString())
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()

        val json = JSONObject(output)

        val auc1Ref = json.getDouble("auc1")
        val auc2Ref = json.getDouble("auc2")
        val zRef = json.getDouble("z")
        val p2Ref = json.getDouble("p2")
        val p1Ref = json.getDouble("p1")

        val tol = 1e-6

        val diffAuc1 = abs(kotlinResult.auc1 - auc1Ref)
        val diffAuc2 = abs(kotlinResult.auc2 - auc2Ref)
        val diffZ = abs(kotlinResult.zScore - zRef)
        val diffP2 = abs(kotlinResult.pTwoSided - p2Ref)
        val diffP1 = abs(kotlinResult.pOneSided - p1Ref)

        println("---- DeLong Cross Validation ----")
        println("Kotlin:  $kotlinResult")
        println("Python:  auc1=$auc1Ref auc2=$auc2Ref z=$zRef p2=$p2Ref p1=$p1Ref")
        println("Diffs:   auc1=$diffAuc1 auc2=$diffAuc2 z=$diffZ p2=$diffP2 p1=$diffP1")

        assertTrue(diffAuc1 < tol, "AUC1 mismatch: $diffAuc1")
        assertTrue(diffAuc2 < tol, "AUC2 mismatch: $diffAuc2")
        assertTrue(diffZ < tol, "Z mismatch: $diffZ")
        assertTrue(diffP2 < tol, "P-value mismatch: $diffP2")
        assertTrue(diffP1 < tol, "P-value mismatch: $diffP1")
    }
}
