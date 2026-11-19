package org.jetbrains.bio.qfarm.evolution.stopping

import org.jetbrains.bio.qfarm.params.StopCriterionParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurvivorNoveltyCriterionTest {

    private fun rule(
        vararg intervals: Pair<Double, Double>,
        attributes: IntArray = IntArray(intervals.size) { it }
    ) = RuleIntervals(
        attributes = attributes,
        lower = DoubleArray(intervals.size) { intervals[it].first },
        upper = DoubleArray(intervals.size) { intervals[it].second }
    )

    private val a = rule(0.0 to 0.2)
    private val b = rule(0.5 to 0.7)
    private val c = rule(0.8 to 0.9)

    private fun criterion(
        minGenerations: Int = 1,
        patience: Int = 2,
        similarityThreshold: Double = 0.5,
        maxNewSurvivors: Int = 0
    ) = SurvivorNoveltyCriterion(
        StopCriterionParams(
            minGenerations = minGenerations,
            patience = patience,
            similarityThreshold = similarityThreshold,
            maxNewSurvivors = maxNewSurvivors
        )
    )

    @Test
    fun `interval overlap is intersection over union`() {
        assertEquals(1.0, intervalOverlap(0.1, 0.3, 0.1, 0.3), 1e-12)
        assertEquals(0.0, intervalOverlap(0.0, 0.1, 0.2, 0.3), 1e-12)
        assertEquals(0.0, intervalOverlap(0.0, 0.2, 0.2, 0.4), 1e-12)
        assertEquals(1.0 / 3.0, intervalOverlap(0.0, 0.2, 0.1, 0.3), 1e-12)
        assertEquals(0.25, intervalOverlap(0.0, 0.4, 0.1, 0.2), 1e-12)
        assertEquals(0.25, intervalOverlap(0.1, 0.2, 0.0, 0.4), 1e-12)
    }

    @Test
    fun `zero-width intervals are equal only at the same point`() {
        assertEquals(1.0, intervalOverlap(0.3, 0.3, 0.3, 0.3))
        assertEquals(0.0, intervalOverlap(0.3, 0.3, 0.4, 0.4))
    }

    @Test
    fun `rule similarity is the smallest attribute overlap`() {
        val first = rule(0.0 to 0.2, 0.5 to 0.7)
        val second = rule(0.0 to 0.2, 0.6 to 0.8)

        assertEquals(1.0 / 3.0, ruleSimilarity(first, second), 1e-12)
        assertEquals(1.0, ruleSimilarity(first, first), 1e-12)
    }

    @Test
    fun `rules on different attributes are not similar`() {
        val first = rule(0.0 to 0.2, attributes = intArrayOf(1))
        val second = rule(0.0 to 0.2, attributes = intArrayOf(2))

        assertEquals(0.0, ruleSimilarity(first, second))
    }

    @Test
    fun `first generation counts every survivor as new`() {
        val observation = criterion().updateIntervals(1, listOf(a, b, c))

        assertEquals(3.0, observation.signal)
        assertFalse(observation.shouldStop)
    }

    @Test
    fun `first generation is never stagnant even when maxNewSurvivors allows it`() {
        val criterion = criterion(patience = 1, maxNewSurvivors = 5)

        assertFalse(criterion.updateIntervals(1, listOf(a)).shouldStop)
        assertTrue(criterion.updateIntervals(2, listOf(a)).shouldStop)
    }

    @Test
    fun `fires after patience stagnant generations and not before minGenerations`() {
        val criterion = criterion(minGenerations = 5, patience = 2)
        val survivors = listOf(a, b)

        val stops = (1L..6L).map { criterion.updateIntervals(it, survivors).shouldStop }

        // g1 all new; g2, g3 stagnant (streak reaches 2 at g3), but minGenerations = 5.
        assertEquals(listOf(false, false, false, false, true, true), stops)
    }

    @Test
    fun `a new survivor resets the streak`() {
        val criterion = criterion(patience = 2)

        assertFalse(criterion.updateIntervals(1, listOf(a)).shouldStop)
        assertFalse(criterion.updateIntervals(2, listOf(a)).shouldStop)
        assertTrue(criterion.updateIntervals(3, listOf(a)).shouldStop)

        val withNew = criterion.updateIntervals(4, listOf(a, b))
        assertEquals(1.0, withNew.signal)
        assertFalse(withNew.shouldStop)

        assertFalse(criterion.updateIntervals(5, listOf(a, b)).shouldStop)
        assertTrue(criterion.updateIntervals(6, listOf(a, b)).shouldStop)
    }

    @Test
    fun `a rule dropped for less than patience generations is not new when it returns`() {
        val criterion = criterion(patience = 3)

        criterion.updateIntervals(1, listOf(a, b))
        criterion.updateIntervals(2, listOf(a))
        criterion.updateIntervals(3, listOf(a))

        assertEquals(0.0, criterion.updateIntervals(4, listOf(a, b)).signal)
    }

    @Test
    fun `a rule dropped for longer than patience generations is new when it returns`() {
        val criterion = criterion(patience = 2)

        criterion.updateIntervals(1, listOf(a, b))
        criterion.updateIntervals(2, listOf(a))
        criterion.updateIntervals(3, listOf(a))

        assertEquals(1.0, criterion.updateIntervals(4, listOf(a, b)).signal)
    }

    @Test
    fun `small shifts are the same rule, large shifts are new`() {
        val criterion = criterion(similarityThreshold = 0.5)
        criterion.updateIntervals(1, listOf(rule(0.0 to 0.5)))

        // overlap 0.48 / 0.52 = 0.92
        assertEquals(0.0, criterion.updateIntervals(2, listOf(rule(0.02 to 0.52))).signal)
        // overlap 0.2 / 0.8 = 0.25
        assertEquals(1.0, criterion.updateIntervals(3, listOf(rule(0.3 to 0.8))).signal)
    }

    @Test
    fun `maxNewSurvivors tolerates a few new survivors per generation`() {
        val criterion = criterion(patience = 2, maxNewSurvivors = 1)

        criterion.updateIntervals(1, listOf(a))
        assertFalse(criterion.updateIntervals(2, listOf(a, b)).shouldStop)
        assertTrue(criterion.updateIntervals(3, listOf(a, b, c)).shouldStop)
    }
}

class FrontSummaryTest {

    @Test
    fun `hypervolume and point count of the non-dominated staircase`() {
        val supports = doubleArrayOf(100.0, 50.0, 10.0, 50.0, 5.0, 50.0)
        val confidences = doubleArrayOf(0.2, 0.5, 0.9, 0.4, 0.9, 0.5)

        val summary = frontSummary(supports, confidences, rows = 100)

        // 1.0 * 0.2 + 0.5 * 0.3 + 0.1 * 0.4
        assertEquals(0.39, summary.hypervolume, 1e-9)
        assertEquals(3, summary.points)
    }

    @Test
    fun `zero-confidence point counts as a front point without adding area`() {
        val summary = frontSummary(doubleArrayOf(100.0, 10.0), doubleArrayOf(0.0, 0.5), rows = 100)

        assertEquals(0.05, summary.hypervolume, 1e-9)
        assertEquals(2, summary.points)
    }
}
