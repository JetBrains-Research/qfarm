package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.ext.moea.Pareto
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.params.StopCriterionParams
import java.util.SplittableRandom
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrowdingStabilityCriterionTest {

    private fun randomPoints(n: Int, seed: Long): List<DoubleArray> {
        val r = SplittableRandom(seed)
        // coarse values, so there are ties and duplicates as with real supports
        return List(n) { doubleArrayOf(r.nextInt(1, 60).toDouble(), r.nextInt(0, 25) / 25.0) }
    }

    @Test
    fun `first front is the set of distinct non-dominated points`() {
        for (seed in 1L..30L) {
            val pts = randomPoints(120, seed)
            val dominated = { p: DoubleArray, q: DoubleArray -> q[0] >= p[0] && q[1] >= p[1] && (q[0] > p[0] || q[1] > p[1]) }
            val expected = pts.filter { p -> pts.none { q -> dominated(p, q) } }
                .map { it.toList() }.distinct().sortedByDescending { it[0] }
            assertEquals(expected, firstFront(pts).map { it.toList() }, "seed $seed")
        }
    }

    @Test
    fun `crowding distance equals Jenetics Pareto crowdingDistance on the front`() {
        for (seed in 1L..30L) {
            val front = firstFront(randomPoints(150, seed))
            val reference = Pareto.crowdingDistance(ISeq.of(front.map { Vec.of(*it) }))
            val ours = crowdingDistances(front)
            assertEquals(reference.size, ours.size)
            for (i in ours.indices) assertEquals(reference[i], ours[i], 1e-12, "seed $seed, point $i")
        }
    }

    @Test
    fun `largest finite crowding distance, worked example`() {
        val pts = listOf(
            doubleArrayOf(100.0, 0.2), doubleArrayOf(60.0, 0.4), doubleArrayOf(30.0, 0.6), doubleArrayOf(10.0, 0.9),
            doubleArrayOf(50.0, 0.3), doubleArrayOf(60.0, 0.4)   // dominated point and a duplicate
        )
        // interior (60, 0.4): (100-30)/90 + (0.6-0.2)/0.7; interior (30, 0.6): (60-10)/90 + (0.9-0.4)/0.7
        assertEquals(70.0 / 90 + 0.4 / 0.7, maxCrowding(pts), 1e-12)
    }

    @Test
    fun `max crowding is undefined with fewer than three front points`() {
        assertTrue(maxCrowding(listOf(doubleArrayOf(10.0, 0.5), doubleArrayOf(5.0, 0.8))).isNaN())
        assertTrue(maxCrowding(listOf(doubleArrayOf(10.0, 0.5), doubleArrayOf(5.0, 0.4))).isNaN())
    }

    private fun criterion(window: Int = 3, maxStd: Double = 0.01, minGen: Int = 1) =
        CrowdingStabilityCriterion(StopCriterionParams(windowLength = window, maxStdDev = maxStd, minGenerations = minGen))

    @Test
    fun `fires once the window is full and stable`() {
        val c = criterion()
        assertFalse(c.updateIndicator(1, 0.5).shouldStop)
        assertTrue(c.updateIndicator(1, 0.5).signal.isNaN())
        val third = c.updateIndicator(3, 0.5)
        assertEquals(0.0, third.signal, 1e-12)
        assertTrue(third.shouldStop)
    }

    @Test
    fun `signal is the population standard deviation of the window`() {
        val c = criterion(window = 4, maxStd = 0.0)
        listOf(1.0, 2.0, 3.0).forEachIndexed { i, d -> c.updateIndicator(i + 1L, d) }
        val o = c.updateIndicator(4, 4.0)
        assertEquals(sqrt(1.25), o.signal, 1e-12)   // values 1, 2, 3, 4
        assertEquals(4.0, o.indicator)
        assertFalse(o.shouldStop)
        // one more value: the window slides to 2, 3, 4, 5 (mean 3.5)
        assertEquals(sqrt(1.25), c.updateIndicator(5, 5.0).signal, 1e-12)
        // and then to 3, 4, 5, 3 (mean 3.75)
        assertEquals(sqrt((0.5625 + 0.0625 + 1.5625 + 0.5625) / 4), c.updateIndicator(6, 3.0).signal, 1e-12)
    }

    @Test
    fun `window slides over the last L values`() {
        val c = criterion(window = 3, maxStd = 0.01)
        listOf(5.0, 1.0, 1.0).forEachIndexed { i, d -> assertFalse(c.updateIndicator(i + 1L, d).shouldStop) }
        assertTrue(c.updateIndicator(4, 1.0).shouldStop)    // window is now 1, 1, 1
    }

    @Test
    fun `undefined indicator clears the window`() {
        val c = criterion(window = 3)
        c.updateIndicator(1, 0.5); c.updateIndicator(2, 0.5)
        assertFalse(c.updateIndicator(3, Double.NaN).shouldStop)
        assertFalse(c.updateIndicator(4, 0.5).shouldStop)
        assertFalse(c.updateIndicator(5, 0.5).shouldStop)
        assertTrue(c.updateIndicator(6, 0.5).shouldStop)
    }

    @Test
    fun `does not fire before minGenerations`() {
        val c = criterion(window = 2, minGen = 5)
        val stops = (1L..6L).map { c.updateIndicator(it, 0.3).shouldStop }
        assertContentEquals(listOf(false, false, false, false, true, true), stops)
    }
}

class FrontSummaryTest {

    @Test
    fun `hypervolume and point count of the non-dominated staircase`() {
        val pts = listOf(100.0 to 0.2, 50.0 to 0.5, 10.0 to 0.9, 50.0 to 0.4, 5.0 to 0.9, 50.0 to 0.5)
            .map { doubleArrayOf(it.first, it.second) }
        val summary = frontSummary(pts, rows = 100)
        // 1.0 * 0.2 + 0.5 * 0.3 + 0.1 * 0.4
        assertEquals(0.39, summary.hypervolume, 1e-9)
        assertEquals(3, summary.points)
    }
}
