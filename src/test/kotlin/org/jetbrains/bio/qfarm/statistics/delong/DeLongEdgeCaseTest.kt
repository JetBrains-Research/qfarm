package org.jetbrains.bio.qfarm.statistics.delong

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.math.abs

class DeLongEdgeCaseTest {

    private val tol = 1e-12

    // ==========================================================
    // Logging helpers (consistent with structured test logging)
    // ==========================================================

    private fun logCase(name: String) {
        println("--------------------------------------------------")
        println("Running test case: $name")
    }

    private fun logResult(result: DeLongResult) {
        println(
            "Result => " +
                    "auc1=${result.auc1}, " +
                    "auc2=${result.auc2}, " +
                    "z=${result.zScore}, " +
                    "pTwo=${result.pTwoSided}, " +
                    "pOne=${result.pOneSided}"
        )
    }

    private fun assertAlmostEquals(
        expected: Double,
        actual: Double,
        name: String
    ) {
        val diff = abs(expected - actual)
        assertTrue(
            diff < tol,
            "$name mismatch.\nExpected: $expected\nActual:   $actual\nDiff:     $diff\nTolerance:$tol"
        )
    }

    // ==========================================================
    // 1️⃣ Identical Models
    // ==========================================================

    @Test
    fun identicalModels() {
        logCase("Identical Models")

        val labels = intArrayOf(1, 1, 0, 0)
        val scores = doubleArrayOf(0.9, 0.8, 0.4, 0.3)

        val result = DeLong.compare(labels, scores, scores)
        logResult(result)

        assertAlmostEquals(0.0, result.zScore, "zScore")
        assertAlmostEquals(1.0, result.pTwoSided, "pTwoSided")
        assertAlmostEquals(1.0, result.pOneSided, "pOneSided")
    }

    // ==========================================================
    // 2️⃣ Perfect Separation
    // ==========================================================

    @Test
    fun perfectSeparation() {
        logCase("Perfect Separation")

        val labels = intArrayOf(1, 1, 1, 0, 0, 0)
        val s1 = doubleArrayOf(0.9, 0.8, 0.7, 0.3, 0.2, 0.1)
        val s2 = doubleArrayOf(0.95, 0.85, 0.75, 0.25, 0.15, 0.05)

        val result = DeLong.compare(labels, s1, s2)
        logResult(result)

        assertTrue(result.pTwoSided.isFinite())
        assertTrue(result.pOneSided.isFinite())
    }

    // ==========================================================
    // 3️⃣ Swapping Models Flips Sign
    // ==========================================================

    @Test
    fun swappingModelsFlipsSign() {
        logCase("Swapping Models")

        val labels = intArrayOf(1, 1, 0, 0, 1, 0)
        val s1 = doubleArrayOf(0.8, 0.7, 0.4, 0.3, 0.6, 0.2)
        val s2 = doubleArrayOf(0.9, 0.75, 0.45, 0.35, 0.65, 0.25)

        val forward = DeLong.compare(labels, s1, s2)
        val backward = DeLong.compare(labels, s2, s1)

        logResult(forward)
        logResult(backward)

        assertAlmostEquals(forward.zScore, -backward.zScore, "zScore symmetry")
    }

    // ==========================================================
    // 4️⃣ Heavy Ties
    // ==========================================================

    @Test
    fun heavyTies() {
        logCase("Heavy Ties")

        val labels = intArrayOf(1, 1, 0, 0, 1, 0)
        val s1 = doubleArrayOf(0.5, 0.5, 0.5, 0.5, 0.5, 0.5)
        val s2 = doubleArrayOf(0.6, 0.6, 0.6, 0.6, 0.6, 0.6)

        val result = DeLong.compare(labels, s1, s2)
        logResult(result)

        assertTrue(result.pTwoSided.isFinite())
        assertTrue(result.pOneSided.isFinite())
    }

    // ==========================================================
    // 5️⃣ Single Positive
    // ==========================================================

    @Test
    fun singlePositiveShouldFail() {

        val labels = intArrayOf(1, 0, 0, 0)
        val s1 = doubleArrayOf(0.9, 0.8, 0.7, 0.6)
        val s2 = doubleArrayOf(0.95, 0.85, 0.75, 0.65)

        try {
            DeLong.compare(labels, s1, s2)
            assertTrue(false, "Expected exception for single positive")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    // ==========================================================
    // 6️⃣ Single Negative
    // ==========================================================

    @Test
    fun singleNegativeShouldFail() {

        val labels = intArrayOf(1, 1, 1, 0)
        val s1 = doubleArrayOf(0.9, 0.8, 0.7, 0.6)
        val s2 = doubleArrayOf(0.95, 0.85, 0.75, 0.65)

        try {
            DeLong.compare(labels, s1, s2)
            assertTrue(false, "Expected exception for single negative")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    // ==========================================================
    // 7️⃣ Nearly Identical Scores
    // ==========================================================

    @Test
    fun nearlyIdenticalScores() {
        logCase("Nearly Identical Scores")

        val labels = intArrayOf(1, 1, 0, 0)
        val s1 = doubleArrayOf(0.9, 0.8, 0.4, 0.3)
        val s2 = doubleArrayOf(0.9000000001, 0.8000000001, 0.4, 0.3)

        val result = DeLong.compare(labels, s1, s2)
        logResult(result)

        assertTrue(abs(result.zScore) < 1.0)
        assertTrue(result.pTwoSided.isFinite())
    }

    // ==========================================================
    // 8️⃣ Extreme Probabilities (0 and 1)
    // ==========================================================

    @Test
    fun extremeProbabilities() {
        logCase("Extreme Probabilities")

        val labels = intArrayOf(1, 1, 0, 0)
        val s1 = doubleArrayOf(1.0, 1.0, 0.0, 0.0)
        val s2 = doubleArrayOf(0.9, 0.8, 0.2, 0.1)

        val result = DeLong.compare(labels, s1, s2)
        logResult(result)

        assertTrue(result.pTwoSided.isFinite())
        assertTrue(result.pOneSided.isFinite())
    }

    // ==========================================================
    // 9️⃣ Degenerate Labels (All Same) Should Fail
    // ==========================================================

    @Test
    fun allLabelsSameShouldFail() {
        logCase("Degenerate Labels")

        val labels = intArrayOf(1, 1, 1, 1)
        val s1 = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
        val s2 = doubleArrayOf(0.2, 0.3, 0.4, 0.5)

        try {
            DeLong.compare(labels, s1, s2)
            assertTrue(false, "Expected exception for degenerate labels")
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun permutationInvariance() {

        val labels = intArrayOf(1,1,0,0,1,0)
        val s1 = doubleArrayOf(0.8,0.7,0.4,0.3,0.6,0.2)
        val s2 = doubleArrayOf(0.9,0.75,0.45,0.35,0.65,0.25)

        val original = DeLong.compare(labels, s1, s2)

        val perm = listOf(3,0,5,1,4,2)

        val labelsPerm = perm.map { labels[it] }.toIntArray()
        val s1Perm = perm.map { s1[it] }.toDoubleArray()
        val s2Perm = perm.map { s2[it] }.toDoubleArray()

        val permuted = DeLong.compare(labelsPerm, s1Perm, s2Perm)

        assertAlmostEquals(original.zScore, permuted.zScore, "Permutation invariance")
    }

    @Test
    fun containsNaNShouldFail() {

        val labels = intArrayOf(1, 1, 0, 0)
        val s1 = doubleArrayOf(0.9, Double.NaN, 0.4, 0.3)
        val s2 = doubleArrayOf(0.95, 0.85, 0.45, 0.35)

        try {
            DeLong.compare(labels, s1, s2)
            assertTrue(false, "Expected exception due to NaN in scores1")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

}
