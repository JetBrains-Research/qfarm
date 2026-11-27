package org.jetbrains.bio.qfarm.evaluation.scan

import org.jetbrains.bio.qfarm.evaluation.random.buildWindowSizes
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.IndexPool
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.evolution.SortedColumnsPercentileProvider
import org.jetbrains.bio.qfarm.params.CheapScanParams
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.util.DatasetWithHeader
import org.jetbrains.bio.qfarm.util.computeBoundsFromSorted
import org.jetbrains.bio.qfarm.util.computeSortedColumns
import org.jetbrains.bio.qfarm.util.detectDiscreteColumns
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheapScanTest {

    // ------------------------------------------------------------------ fixtures

    /** Columns 0..d-1 are attributes, the last column is the RHS (unused by the scan). */
    private fun env(data: List<DoubleArray>, labels: IntArray): EvolutionEnvironment {
        val header = data[0].indices.map { "c$it" }
        val sorted = computeSortedColumns(data)
        return EvolutionEnvironment(
            datasetWithHeader = DatasetWithHeader(header, data, labels),
            columnNames = header,
            sortedColumns = sorted,
            bounds = computeBoundsFromSorted(sorted),
            percentileProvider = SortedColumnsPercentileProvider(sorted),
            discreteInfo = detectDiscreteColumns(sorted, maxDistinct = 16),
            rightAttrIndex = data[0].size - 1
        )
    }

    private fun randomData(n: Int, seed: Long, discreteLevels: Int?): Pair<List<DoubleArray>, IntArray> {
        val r = SplittableRandom(seed)
        val data = List(n) {
            doubleArrayOf(
                if (discreteLevels == null) r.nextDouble() else r.nextInt(discreteLevels).toDouble(),
                r.nextDouble(),
                r.nextInt(4).toDouble(),
                0.0
            )
        }
        val labels = IntArray(n) { if (r.nextDouble() < 0.3) 1 else 0 }
        return data to labels
    }

    private fun rowsInside(data: List<DoubleArray>, box: ScanBox): List<Int> =
        data.indices.filter { r ->
            box.attributes.indices.all { i ->
                val v = data[r][box.attributes[i]]
                v >= box.lo[i] && v <= box.hi[i]
            }
        }

    /**
     * The scan's reduction of candidate (support, positives) pairs: best positives per support, one
     * representative per window-size bin of `buildWindowSizes` (highest confidence), then the
     * (support, confidence) Pareto set with the dominance of `paretoFilterWindows`.
     */
    private fun reduce(points: Collection<Pair<Int, Int>>, population: Int, cap: Int): Set<Pair<Int, Int>> {
        val best = HashMap<Int, Int>()
        for ((s, p) in points) if (p > (best[s] ?: -1)) best[s] = p
        val sizes = buildWindowSizes(hp.minSupport, cap, population)
        val reps = ArrayList<Pair<Int, Int>>()
        var prev = 0
        for (cut in sizes) {
            val hi = min(cut, cap)
            val candidates = ((prev + 1)..hi).mapNotNull { s -> best[s]?.let { s to it } }
            candidates.maxByOrNull { it.second.toDouble() / it.first }?.let { reps += it }
            prev = hi
            if (prev >= cap) break
        }
        for (s in (prev + 1)..cap) best[s]?.let { reps += s to it }
        fun conf(p: Pair<Int, Int>) = p.second.toDouble() / p.first
        return reps.filter { p ->
            reps.none { q -> q != p && q.first >= p.first && conf(q) >= conf(p) && (q.first > p.first || conf(q) > conf(p)) }
        }.toSet()
    }

    private fun supportCap(n: Int) = min(hp.maxSupport, (hp.maxWidth * n + hp.minSupport).toInt()).coerceIn(1, n)

    // ------------------------------------------------------------------ depth 1

    @Test
    fun `depth-1 boxes are exact rules and reduce all group-aligned windows as the scan does (few distinct values)`() {
        for (seed in 1L..5L) {
            val (data, labels) = randomData(n = 400, seed = seed, discreteLevels = 7)
            val e = env(data, labels)
            val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 1)).scan(listOf(0), null)

            for (b in result.boxes) {
                val rows = rowsInside(data, b)
                assertEquals(b.support, rows.size, "support of box $b")
                assertEquals(b.positives, rows.count { labels[it] == 1 }, "positives of box $b")
            }

            val values = data.map { it[0] }.distinct().sorted()
            val cap = supportCap(data.size)
            val windows = ArrayList<Pair<Int, Int>>()
            for (i in values.indices) for (j in i until values.size) {
                val rows = data.indices.filter { data[it][0] >= values[i] && data[it][0] <= values[j] }
                if (rows.size in hp.minSupport..cap) windows += rows.size to rows.count { labels[it] == 1 }
            }
            assertEquals(reduce(windows, data.size, cap), result.boxes.map { it.support to it.positives }.toSet(), "seed $seed")
        }
    }

    @Test
    fun `depth-1 boxes on a continuous attribute reduce the best window per size as the scan does`() {
        for (seed in 1L..3L) {
            val (data, labels) = randomData(n = 2000, seed = seed, discreteLevels = null)   // > GROUP_ENUMERATION_LIMIT distinct values
            val e = env(data, labels)
            val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 1)).scan(listOf(0), null)

            for (b in result.boxes) {
                val rows = rowsInside(data, b)
                assertEquals(b.support, rows.size)
                assertEquals(b.positives, rows.count { labels[it] == 1 })
            }

            val order = data.indices.sortedBy { data[it][0] }
            val lab = IntArray(order.size) { labels[order[it]] }
            val prefix = IntArray(lab.size + 1)
            for (p in lab.indices) prefix[p + 1] = prefix[p] + lab[p]
            val cap = supportCap(data.size)
            val best = ArrayList<Pair<Int, Int>>()
            for (k in buildWindowSizes(hp.minSupport, cap, data.size)) {
                var bestPos = -1
                for (s in 0..(lab.size - k)) bestPos = maxOf(bestPos, prefix[s + k] - prefix[s])
                best += k to bestPos
            }
            assertEquals(reduce(best, data.size, cap), result.boxes.map { it.support to it.positives }.toSet(), "seed $seed")
        }
    }

    // ------------------------------------------------------------------ depth >= 2

    /** The scan's binning, reimplemented: percentile bins by rank, equal values share the bin of their first position. */
    private fun bins(values: DoubleArray, k: Int): IntArray {
        val n = values.size
        val order = values.indices.sortedWith(compareBy({ values[it] }, { it }))
        val rank = IntArray(n)
        order.forEachIndexed { p, r -> rank[r] = p }
        val groupStartOfPos = IntArray(n)
        var p = 0
        while (p < n) {
            var q = p + 1
            while (q < n && values[order[q]] == values[order[p]]) q++
            for (i in p until q) groupStartOfPos[i] = p
            p = q
        }
        return IntArray(n) { r -> min(k - 1, (groupStartOfPos[rank[r]].toLong() * k / n).toInt()) }
    }

    private fun gridBruteForce(data: List<DoubleArray>, labels: IntArray, attrs: List<Int>, k: Int): Set<Pair<Int, Int>> {
        val n = data.size
        val d = attrs.size
        val rowBins = attrs.map { a -> bins(DoubleArray(n) { data[it][a] }, k) }
        val maxLen = maxOf(1, floor(hp.maxWidth * k).toInt())
        val cap = hp.maxSupport.coerceIn(1, n)
        val boxes = ArrayList<Pair<Int, Int>>()
        val lo = IntArray(d)
        val hi = IntArray(d)
        fun rec(dim: Int) {
            if (dim == d) {
                var support = 0
                var positives = 0
                for (r in 0 until n) {
                    var inside = true
                    for (i in 0 until d) if (rowBins[i][r] < lo[i] || rowBins[i][r] > hi[i]) { inside = false; break }
                    if (inside) { support++; if (labels[r] == 1) positives++ }
                }
                if (support in hp.minSupport..cap) boxes += support to positives
                return
            }
            for (l in 0 until k) for (h in l until min(k, l + maxLen)) {
                lo[dim] = l; hi[dim] = h
                rec(dim + 1)
            }
        }
        rec(0)
        return reduce(boxes, n, cap)
    }

    @Test
    fun `grid boxes are exact rules and reduce all grid boxes as the scan does (depth 2 and 3, with ties)`() {
        for ((seed, attrs) in listOf(1L to listOf(0, 1), 2L to listOf(1, 2), 3L to listOf(0, 1, 2))) {
            val (data, labels) = randomData(n = 300, seed = seed, discreteLevels = 5)
            val e = env(data, labels)
            val scanner = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 1))
            val result = scanner.scan(attrs, null)
            val k = scanner.gridBins(attrs.size)

            for (b in result.boxes) {
                val rows = rowsInside(data, b)
                assertEquals(b.support, rows.size, "support of a box over $attrs")
                assertEquals(b.positives, rows.count { labels[it] == 1 }, "positives of a box over $attrs")
            }
            assertEquals(gridBruteForce(data, labels, attrs, k), result.boxes.map { it.support to it.positives }.toSet(), "attrs $attrs")
        }
    }

    // ------------------------------------------------------------------ modes, seeds, statistics

    @Test
    fun `a second mode is found among the rows the first mode leaves, and only its knee boxes are added`() {
        val n = 1000
        val data = List(n) { i -> doubleArrayOf(i / (n - 1.0), 0.0) }
        val labels = IntArray(n) { i ->
            val x = data[i][0]
            if ((x >= 0.10 && x <= 0.20) || (x >= 0.70 && x <= 0.75)) 1 else 0
        }
        val e = env(data, labels)
        val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 2)).scan(listOf(0), null)

        assertEquals(2, result.modes)
        val second = result.boxes.filter { it.pass == 2 }
        assertTrue(second.isNotEmpty())
        // The second pass contributes only its knee boxes: all (nearly) inside the second interval,
        // x = i/999 in [0.70, 0.75] <=> i in 700..749, 50 rows. The window sizes searched step by
        // about 2.7 rows at n = 1000, so the knee box may take one or two neighbouring rows.
        for (b in second) {
            assertTrue(b.lo[0] >= 0.69 && b.hi[0] <= 0.76, "pass-2 box outside the second mode: ${b.lo[0]}..${b.hi[0]}")
            assertTrue(b.confidence >= 0.95, "pass-2 box should be nearly pure, was ${b.confidence}")
            assertEquals(rowsInside(data, b).size, b.support)
            assertEquals(rowsInside(data, b).count { labels[it] == 1 }, b.positives)
        }
        assertTrue(second.maxOf { it.positives } >= 48, "the knee box should cover (almost) the whole second interval")
        assertNotNull(second.firstOrNull { it.support in 48..53 }, "a knee box of about 50 rows is expected")

        val one = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 1)).scan(listOf(0), null)
        assertEquals(1, one.modes)
        assertTrue(result.auc >= one.auc - 1e-12, "the second mode must not lower the ranking AUC")
    }

    @Test
    fun `two enriched tails with a weaker middle are two modes (the X2 case of friedman1)`() {
        // positive rate ~0.30 for x <= 0.2 and x >= 0.8, ~0.10 in between; deterministic pattern
        val n = 2000
        val data = List(n) { i -> doubleArrayOf(i / (n - 1.0), 0.0) }
        val labels = IntArray(n) { i ->
            val x = data[i][0]
            if (x <= 0.2 || x >= 0.8) (if (i % 10 < 3) 1 else 0) else (if (i % 10 == 0) 1 else 0)
        }
        val e = env(data, labels)
        val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 2)).scan(listOf(0), null)
        assertEquals(2, result.modes, "both tails must be found")

        val base = labels.sum().toDouble() / n
        val pass1Knee = result.boxes.filter { it.pass == 1 }.maxBy { it.support.toDouble() / n * (it.confidence - base) }
        val pass2 = result.boxes.filter { it.pass == 2 }
        val pass2Knee = pass2.maxBy { it.support }
        // one knee per tail, in either order
        val tails = listOf(pass1Knee, pass2Knee).sortedBy { it.lo[0] }
        assertTrue(tails[0].hi[0] <= 0.25 && tails[0].lo[0] <= 0.02, "left tail knee expected, got ${tails[0].lo[0]}..${tails[0].hi[0]}")
        assertTrue(tails[1].lo[0] >= 0.75 && tails[1].hi[0] >= 0.98, "right tail knee expected, got ${tails[1].lo[0]}..${tails[1].hi[0]}")
        // pass-2 boxes are nested inside the pass-2 knee and carry true counts
        for (b in pass2) {
            assertTrue(b.lo[0] >= pass2Knee.lo[0] - 1e-9 && b.hi[0] <= pass2Knee.hi[0] + 1e-9)
            assertEquals(rowsInside(data, b).size, b.support)
            assertEquals(rowsInside(data, b).count { labels[it] == 1 }, b.positives)
        }
        val one = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 1)).scan(listOf(0), null)
        assertTrue(result.auc > one.auc, "the second tail must raise the ranking AUC (${one.auc} -> ${result.auc})")
    }

    @Test
    fun `the shoulder of a monotone attribute is not a second mode`() {
        // positive rate 1 for x >= 0.8, 0.5 on [0.6, 0.8), 0 below: one region with a falling edge
        val n = 1000
        val data = List(n) { i -> doubleArrayOf(i / (n - 1.0), 0.0) }
        val labels = IntArray(n) { i ->
            val x = data[i][0]
            when {
                x >= 0.8 -> 1
                x >= 0.6 -> if (i % 2 == 0) 1 else 0
                else -> 0
            }
        }
        val e = env(data, labels)
        val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 2)).scan(listOf(0), null)
        assertEquals(1, result.modes, "the band next to the knee is the same region, not a second mode")
        assertTrue(result.boxes.none { it.pass == 2 })
    }

    @Test
    fun `a second mode is rarely claimed on pure noise`() {
        // Bonferroni controls the family-wise rate per pass at modeAlpha; with 0.01 over 20 noise
        // attributes the expected number of false modes is 0.2.
        var claimed = 0
        for (seed in 1L..20L) {
            val (data, labels) = randomData(n = 1000, seed = seed, discreteLevels = null)
            val e = env(data, labels)
            val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 2, modeAlpha = 0.01)).scan(listOf(0), null)
            if (result.modes > 1) claimed++
        }
        assertTrue(claimed <= 1, "a second mode was claimed on noise in $claimed of 20 attributes")
    }

    @Test
    fun `boxes become valid seed genotypes with the box bounds, and seeds are thinned evenly`() {
        val (data, labels) = randomData(n = 300, seed = 4L, discreteLevels = null)
        val e = env(data, labels)
        val attrs = listOf(0, 1)
        val result = CheapScan(e, e.columnOrders, CheapScanParams(maxModes = 1)).scan(attrs, null)
        val cfg = RuleInitConfig(
            rightAttrIndex = e.rightAttrIndex,
            bounds = e.bounds,
            percentile = e.percentileProvider,
            fixedAttributes = attrs
        )
        val pool = IndexPool(emptySet())
        var seeds = 0
        for (b in result.boxes) {
            val gt = CheapScan.seedGenotype(b, attrs, cfg, pool) ?: continue
            seeds++
            assertTrue(gt.isValid)
            val lhs = gt[0]
            for (i in attrs.indices) {
                val g = lhs[i]
                assertEquals(attrs[i], g.attributeIndex)
                assertEquals(b.lo[i], g.lowerBound)
                assertEquals(b.hi[i], g.upperBound)
                assertTrue(g.pLeft <= g.pRight && g.pRight - g.pLeft <= hp.maxWidth + 1e-12)
            }
        }
        assertTrue(seeds > 0, "at least the narrow boxes must be seedable")

        val thinned = CheapScan.thinSeeds(result.boxes, 5)
        assertEquals(min(5, result.boxes.size), thinned.size)
        assertEquals(thinned.distinct().size, thinned.size)
        assertEquals(result.boxes, CheapScan.thinSeeds(result.boxes, result.boxes.size + 10))
    }

    @Test
    fun `hypergeometric tail matches known values`() {
        val orders = ColumnOrders(List(10) { doubleArrayOf(it.toDouble()) })
        assertEquals(1.0 / 252.0, orders.hypergeometricTail(n = 10, k = 5, s = 5, x = 5), 1e-12)
        assertEquals(1.0, orders.hypergeometricTail(n = 10, k = 5, s = 5, x = 0))
        // P(X >= 3) for N=10, K=5, s=5: (C(5,3)C(5,2) + C(5,4)C(5,1) + C(5,5)C(5,0)) / 252 = (100 + 25 + 1) / 252
        assertEquals(126.0 / 252.0, orders.hypergeometricTail(10, 5, 5, 3), 1e-12)
        assertTrue(abs(orders.logChoose(10, 5) - Math.log(252.0)) < 1e-9)
    }
}
