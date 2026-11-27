package org.jetbrains.bio.qfarm.evaluation.scan

import io.jenetics.Genotype
import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.core.RuleSideChromosome
import org.jetbrains.bio.qfarm.evaluation.random.buildBestWindowsForShuffleDetailed
import org.jetbrains.bio.qfarm.evaluation.random.buildWindowSizes
import org.jetbrains.bio.qfarm.evolution.EvolutionEnvironment
import org.jetbrains.bio.qfarm.evolution.IndexPool
import org.jetbrains.bio.qfarm.evolution.RuleInitConfig
import org.jetbrains.bio.qfarm.params.CheapScanParams
import org.jetbrains.bio.qfarm.params.RocComparisonMode
import org.jetbrains.bio.qfarm.params.cheapScanParams
import org.jetbrains.bio.qfarm.params.hp
import org.jetbrains.bio.qfarm.statistics.delong.AUC
import kotlin.math.floor
import kotlin.math.pow

/**
 * One box found by the scan: a closed interval per attribute, i.e. a QFARM rule condition set.
 * `pLo`/`pHi` are the percentile positions of the bounds (position / (rows - 1)), as the genes use.
 * [support] and [positives] are counted on all rows and the true labels.
 */
class ScanBox(
    val attributes: IntArray,
    val lo: DoubleArray,
    val hi: DoubleArray,
    val pLo: DoubleArray,
    val pHi: DoubleArray,
    val support: Int,
    val positives: Int,
    /** 1 = first box set; 2 = the mode found among the rows the first set's knee boxes do not cover; ... */
    val pass: Int
) {
    val confidence: Double get() = if (support == 0) 0.0 else positives.toDouble() / support
}

/** Result of scanning one candidate attribute set. */
class ScanResult(
    /** AUC of the per-row scores, over the rule set given by `hp.rocComparison` (as the cheap evolution's AUC). */
    val auc: Double,
    /** Box sets found: 1 + accepted further modes. */
    val modes: Int,
    /** Boxes of pass 1 (its whole Pareto set) and the knee boxes of every accepted further pass. */
    val boxes: List<ScanBox>,
    /** Boxes evaluated against the constraints, over all passes. */
    val boxesEvaluated: Long,
    /**
     * Bonferroni-adjusted p-value of the best knee box of the second pass against the rows the first
     * mode leaves (null when no second pass ran, 1.0 when it found no box above the base rate).
     * The second mode is accepted when this is below the alpha and the box lies beyond a dip.
     */
    val secondModeP: Double?,
    val seconds: Double
)

/**
 * The parent front seen by the scan: the rules' (support, confidence) and covered rows, and the
 * per-row count of covering parent rules. Computed once per node and shared by its candidates.
 */
class ParentContext(
    val support: IntArray,
    val confidence: DoubleArray,
    val rows: Array<IntArray>,
    val counts: IntArray
) {
    val size: Int get() = support.size

    companion object {
        /** null when there is no parent front (depth 1). Same closed intervals and tolerance as computeFrontScores. */
        fun of(front: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>?, env: EvolutionEnvironment): ParentContext? {
            if (front == null || front.isEmpty) return null
            val data = env.datasetWithHeader.data
            val n = data.size
            val eps = 1e-6
            val support = IntArray(front.size())
            val confidence = DoubleArray(front.size())
            val rows = Array(front.size()) { IntArray(0) }
            val counts = IntArray(n)
            for ((i, pt) in front.withIndex()) {
                val f = pt.fitness().data()
                support[i] = f[0].toInt()
                confidence[i] = f[1]
                val lhs = pt.genotype()[0] as RuleSideChromosome
                val active = (0 until lhs.length()).map { lhs[it] }.filter { !it.isDefault }
                val covered = ArrayList<Int>()
                for (r in 0 until n) {
                    val row = data[r]
                    var inside = true
                    for (g in active) {
                        val v = row[g.attributeIndex]
                        if (v < g.lowerBound - eps || v > g.upperBound + eps) { inside = false; break }
                    }
                    if (inside) { covered += r; counts[r]++ }
                }
                rows[i] = covered.toIntArray()
            }
            return ParentContext(support, confidence, rows, counts)
        }
    }
}

/**
 * Exact box scan of one candidate attribute set, the cheap phase's replacement for the cheap evolution.
 *
 * Depth 1 (one attribute): the best window of rows per window size along the attribute's sorted
 * order, the computation the random-AUC baseline runs on shuffled orders, with windows snapped to
 * runs of equal values; when the attribute has few distinct values every group-aligned window is
 * enumerated and the best per size is taken from those.
 *
 * Depth >= 2: all axis-aligned boxes of a percentile grid over the attributes, counted with
 * d-dimensional prefix sums.
 *
 * The boxes of a pass are thinned to the best box per window-size bin of `buildWindowSizes` and
 * reduced to their (support, confidence) Pareto set. Rows are scored by the number of boxes
 * covering them, combined with the parent front as `hp.rocComparison` says, and summarised by the
 * AUC that ranks the candidate.
 *
 * Further modes: the rows covered by the knee boxes of a pass (the boxes up to the largest WRAcc)
 * are removed, and the scan is repeated on the remaining rows alone. The new pass's knee boxes are
 * accepted as a mode when the best of them is significant against the remaining rows
 * (hypergeometric tail, Bonferroni over the boxes evaluated).
 */
class CheapScan(
    private val env: EvolutionEnvironment,
    private val orders: ColumnOrders,
    private val params: CheapScanParams = cheapScanParams,
    /** Diagnostics only: receives one line per decision of the further-mode passes. */
    private val trace: ((String) -> Unit)? = null
) {
    private val data = env.datasetWithHeader.data
    private val labels = env.datasetWithHeader.labels
    private val n = data.size

    fun scan(attributes: List<Int>, parent: ParentContext?): ScanResult {
        require(attributes.isNotEmpty()) { "attributes must not be empty" }
        require(attributes.distinct().size == attributes.size) { "attributes must not contain duplicates: $attributes" }
        require(labels.size == n) { "labels must be computed before the scan" }

        val t0 = System.nanoTime()

        val active = BooleanArray(n) { true }       // rows not yet explained by an accepted mode
        val allBoxes = ArrayList<ScanBox>()
        val boxCounts = IntArray(n)                 // rows -> number of accepted boxes covering them
        val previousKnees = ArrayList<ScanBox>()    // knee boxes of the accepted passes
        val baseRate = labels.count { it == 1 }.toDouble() / n
        var evaluated = 0L
        var modes = 0
        var secondModeP: Double? = null

        for (pass in 1..params.maxModes) {
            var nActive = 0
            var kActive = 0
            for (r in 0 until n) if (active[r]) { nActive++; if (labels[r] == 1) kActive++ }
            if (kActive == 0 || nActive < hp.minSupport.coerceAtLeast(1)) break

            val set = if (attributes.size == 1) scan1D(attributes[0], active, nActive, pass)
                      else scanGrid(attributes, active, nActive, pass)
            evaluated += set.evaluated
            if (set.boxes.isEmpty()) break

            // the mode of this pass: the box with the largest WRAcc on the pass's population
            val knee = kneeBox(set.boxes, nActive, kActive)
            if (pass == 1) {
                modes++
                allBoxes += set.boxes
                for (r in 0 until n) boxCounts[r] += set.rowCounts[r]
            } else {
                if (knee == null) {
                    if (pass == 2) secondModeP = 1.0
                    trace?.invoke("pass $pass: no box above the residual base rate ${"%.3f".format(kActive.toDouble() / nActive)}")
                    break
                }
                // the boxes of this mode: the knee box and the boxes nested inside it
                val nested = set.boxes.filter { within(knee, it) }
                val p = modePValue(nested, nActive, kActive, set.evaluated)
                if (pass == 2) secondModeP = p
                val tc = trueConfidence(knee)
                trace?.invoke("pass $pass: knee ${describe(knee)} residual=${nActive}/${kActive} nested=${nested.size} p=$p trueConf=${"%.3f".format(tc)} base=${"%.3f".format(baseRate)}")
                if (p >= params.alpha()) { trace?.invoke("  rejected: not significant"); break }
                // the knee box must be a rule QFARM would keep: above the base rate on all rows
                if (tc <= baseRate) { trace?.invoke("  rejected: below the base rate on all rows"); break }
                // a region connected to an earlier mode (no dip in the positive rate between them) is
                // that mode's shoulder, not a mode of its own
                val touching = previousKnees.firstOrNull { a -> connected(a, knee, baseRate) }
                if (touching != null) { trace?.invoke("  rejected: connected to ${describe(touching)}"); break }
                trace?.invoke("  accepted as mode ${modes + 1}")
                modes++
                for (b in nested) {
                    val covered = BooleanArray(n)
                    markBox(b, covered)
                    var support = 0
                    var positives = 0
                    for (r in 0 until n) if (covered[r]) { support++; if (labels[r] == 1) positives++; boxCounts[r]++ }
                    allBoxes += ScanBox(b.attributes, b.lo, b.hi, b.pLo, b.pHi, support, positives, pass)
                }
            }
            if (pass == params.maxModes || knee == null) break

            // the rows of the mode's knee box leave the population of the next pass
            previousKnees += knee
            val covered = BooleanArray(n)
            markBox(knee, covered)
            for (r in 0 until n) if (covered[r]) active[r] = false
        }

        val scores = combinedScores(allBoxes, boxCounts, parent)
        val auc = AUC.compute(labels, scores)

        return ScanResult(
            auc = auc,
            modes = modes,
            boxes = allBoxes,
            boxesEvaluated = evaluated,
            secondModeP = secondModeP,
            seconds = (System.nanoTime() - t0) / 1e9
        )
    }

    // ------------------------------------------------------------------ one pass

    /** Boxes of one pass with support/positives counted on the pass's active rows; rowCounts over all rows. */
    private class BoxSet(val boxes: List<ScanBox>, val rowCounts: IntArray, val evaluated: Long)

    /**
     * The knee box of a pass: the box with the largest WRAcc = support/N * (confidence - base rate)
     * on the pass's population, i.e. Lavrač's unusualness. null when no box beats the base rate.
     */
    private fun kneeBox(boxes: List<ScanBox>, nActive: Int, kActive: Int): ScanBox? {
        val base = kActive.toDouble() / nActive
        var bestW = 0.0
        var knee: ScanBox? = null
        for (b in boxes) {
            val w = b.support.toDouble() / nActive * (b.confidence - base)
            if (w > bestW) {
                bestW = w
                knee = b
            }
        }
        return knee
    }

    /** Whether [inner] lies inside [outer] in every dimension (same attributes, closed intervals). */
    private fun within(outer: ScanBox, inner: ScanBox): Boolean {
        val eps = 1e-6
        for (i in outer.attributes.indices) {
            if (inner.lo[i] < outer.lo[i] - eps || inner.hi[i] > outer.hi[i] + eps) return false
        }
        return true
    }

    private fun describe(b: ScanBox): String =
        b.attributes.indices.joinToString(" ") { i -> "${env.columnNames[b.attributes[i]]}∈[${"%.3f".format(b.lo[i])},${"%.3f".format(b.hi[i])}]" } +
                " s=${b.support} c=${"%.3f".format(b.confidence)}"

    /**
     * Bonferroni-adjusted p-value of the best knee box: hypergeometric tail against the pass's
     * population, times the boxes evaluated, capped at 1. The exact tail is only summed for boxes at
     * least three standard deviations above their expectation: below that the tail exceeds 0.0013,
     * which no correction over hundreds of boxes lets through.
     */
    private fun modePValue(knee: List<ScanBox>, nActive: Int, kActive: Int, evaluated: Long): Double {
        var best = 1.0
        for (b in knee) {
            val s = b.support.toDouble()
            val mean = s * kActive / nActive
            val variance = s * (kActive.toDouble() / nActive) * (1.0 - kActive.toDouble() / nActive) *
                    ((nActive - s) / (nActive - 1.0).coerceAtLeast(1.0))
            if (variance > 0.0 && (b.positives - mean) / Math.sqrt(variance) < 3.0) continue
            val p = orders.hypergeometricTail(nActive, kActive, b.support, b.positives)
            if (p < best) best = p
        }
        return (best * evaluated.coerceAtLeast(1)).coerceAtMost(1.0)
    }

    /** Confidence of a box on all rows and the true labels (boxes of later passes carry residual counts). */
    private fun trueConfidence(box: ScanBox): Double {
        val covered = BooleanArray(n)
        markBox(box, covered)
        var support = 0
        var positives = 0
        for (r in 0 until n) if (covered[r]) { support++; if (labels[r] == 1) positives++ }
        return if (support == 0) 0.0 else positives.toDouble() / support
    }

    /**
     * Whether two boxes over the same attributes are one connected region: the rows of their bounding
     * box that lie in neither box (the bridge) are too few to tell, or have a positive rate at least
     * the base rate of the dataset. A bridge with a lower rate is a dip, and the boxes are separate modes.
     */
    private fun connected(a: ScanBox, b: ScanBox, baseRate: Double): Boolean {
        val eps = 1e-6
        val d = a.attributes.size
        val lo = DoubleArray(d) { minOf(a.lo[it], b.lo[it]) }
        val hi = DoubleArray(d) { maxOf(a.hi[it], b.hi[it]) }
        fun inside(row: DoubleArray, boxLo: DoubleArray, boxHi: DoubleArray): Boolean {
            for (i in 0 until d) {
                val v = row[a.attributes[i]]
                if (v < boxLo[i] - eps || v > boxHi[i] + eps) return false
            }
            return true
        }
        var bridge = 0
        var bridgePositives = 0
        for (r in 0 until n) {
            val row = data[r]
            if (!inside(row, lo, hi) || inside(row, a.lo, a.hi) || inside(row, b.lo, b.hi)) continue
            bridge++
            if (labels[r] == 1) bridgePositives++
        }
        if (bridge < maxOf(10, n / 100)) return true
        return bridgePositives.toDouble() / bridge >= baseRate
    }

    /** Marks the rows inside [box] by its value bounds (closed intervals, as computeFrontScores). */
    private fun markBox(box: ScanBox, mark: BooleanArray) {
        val eps = 1e-6
        for (r in 0 until n) {
            if (mark[r]) continue
            val row = data[r]
            var inside = true
            for (i in box.attributes.indices) {
                val v = row[box.attributes[i]]
                if (v < box.lo[i] - eps || v > box.hi[i] + eps) { inside = false; break }
            }
            if (inside) mark[r] = true
        }
    }

    private fun supportCap(population: Int): Int =
        minOf(hp.maxSupport, (hp.maxWidth * population + hp.minSupport).toInt()).coerceIn(1, population)

    private fun percentileOf(pos: Int): Double = if (n <= 1) 0.0 else pos.toDouble() / (n - 1)

    // ------------------------------------------------------------------ depth 1

    private fun scan1D(attr: Int, active: BooleanArray, nActive: Int, pass: Int): BoxSet {
        val col = orders.of(attr)
        val minSupport = hp.minSupport.coerceIn(1, nActive)
        val cap = supportCap(nActive)
        if (cap < minSupport) return BoxSet(emptyList(), IntArray(n), 0)

        // active rows and active positives along the sorted order, and their prefix sums
        val act = IntArray(n)
        val lab = IntArray(n)
        for (p in 0 until n) {
            val row = col.order[p]
            if (active[row]) {
                act[p] = 1
                if (labels[row] == 1) lab[p] = 1
            }
        }
        val prefixAct = IntArray(n + 1)
        val prefixLab = IntArray(n + 1)
        for (p in 0 until n) {
            prefixAct[p + 1] = prefixAct[p] + act[p]
            prefixLab[p + 1] = prefixLab[p] + lab[p]
        }

        val sizes = buildWindowSizes(minSupport, cap, nActive)
        val buckets = SupportBuckets(cap, slots = 2)
        var evaluated = 0L

        // A window is offered with its active support and positives. From pass 2 on, a window
        // containing rows removed by an earlier pass is not a candidate: it would span the first mode.
        fun offer(start: Int, end: Int) {
            val support = prefixAct[end] - prefixAct[start]
            if (support < minSupport || support > cap) return
            if (pass > 1 && support != end - start) return
            buckets.offer(support, prefixLab[end] - prefixLab[start]) { payload, at ->
                payload[at] = start
                payload[at + 1] = end
            }
        }

        if (col.groups <= GROUP_ENUMERATION_LIMIT) {
            // every window that starts at a group start and ends at a group end
            val starts = IntArray(col.groups)
            val ends = IntArray(col.groups)
            var g = 0
            var p = 0
            while (p < n) {
                starts[g] = p
                ends[g] = col.groupEnd[p]
                p = col.groupEnd[p]
                g++
            }
            for (i in 0 until col.groups) {
                for (j in i until col.groups) {
                    if (prefixAct[ends[j]] - prefixAct[starts[i]] > cap) break
                    evaluated++
                    offer(starts[i], ends[j])
                }
            }
        } else {
            // best window per size on every run of consecutive active positions (pass 1: the whole
            // order), as the random-AUC baseline searches a shuffled order; then snapped to whole groups
            var segStart = 0
            while (segStart < n) {
                if (act[segStart] == 0) { segStart++; continue }
                var segEnd = segStart
                while (segEnd < n && act[segEnd] == 1) segEnd++
                val len = segEnd - segStart
                if (len >= minSupport) {
                    val compact = IntArray(len) { lab[segStart + it] }
                    val segSizes = sizes.filter { it <= len }
                    val best = buildBestWindowsForShuffleDetailed(compact, segSizes)
                    for (w in best.producedWindows) {
                        offer(col.groupStart[segStart + w.start], col.groupEnd[segStart + w.endExclusive - 1])
                    }
                    // hypotheses searched: every start of every size
                    for (k in segSizes) evaluated += (len - k + 1).toLong()
                }
                segStart = segEnd
            }
        }

        val kept = buckets.pareto(sizes)
        val rowCounts = IntArray(n)
        val boxes = ArrayList<ScanBox>(kept.size)
        for (support in kept) {
            val start = buckets.payload(support, 0)
            val end = buckets.payload(support, 1)
            for (p in start until end) rowCounts[col.order[p]]++
            boxes += ScanBox(
                attributes = intArrayOf(attr),
                lo = doubleArrayOf(orders.valueAt(attr, start)),
                hi = doubleArrayOf(orders.valueAt(attr, end - 1)),
                pLo = doubleArrayOf(percentileOf(start)),
                pHi = doubleArrayOf(percentileOf(end - 1)),
                support = support,
                positives = buckets.bestPositives(support),
                pass = pass
            )
        }
        return BoxSet(boxes, rowCounts, evaluated)
    }

    // ------------------------------------------------------------------ depth >= 2

    private class DimBins(
        val rowBin: IntArray,
        /** first and last sorted position of every bin; -1 for an empty bin */
        val firstPos: IntArray,
        val lastPos: IntArray
    )

    /** Percentile bins of one attribute, snapped so that equal values share a bin. */
    private fun bins(attr: Int, k: Int): DimBins {
        val col = orders.of(attr)
        val binOfPos = IntArray(n)
        for (p in 0 until n) {
            val start = col.groupStart[p]
            binOfPos[p] = minOf(k - 1, (start.toLong() * k / n).toInt())
        }
        val rowBin = IntArray(n) { row -> binOfPos[col.rank[row]] }
        val firstPos = IntArray(k) { -1 }
        val lastPos = IntArray(k) { -1 }
        for (p in 0 until n) {
            val b = binOfPos[p]
            if (firstPos[b] < 0) firstPos[b] = p
            lastPos[b] = p
        }
        return DimBins(rowBin, firstPos, lastPos)
    }

    fun gridBins(d: Int): Int {
        val k = floor((n.toDouble() / params.minRowsPerCell).pow(1.0 / d)).toInt()
        return k.coerceIn(2, params.maxBins)
    }

    private fun scanGrid(attributes: List<Int>, active: BooleanArray, nActive: Int, pass: Int): BoxSet {
        val d = attributes.size
        val k = gridBins(d)
        val minSupport = hp.minSupport.coerceIn(1, nActive)
        val cap = hp.maxSupport.coerceIn(1, nActive)
        val maxLen = maxOf(1, floor(hp.maxWidth * k).toInt())
        val sizes = buildWindowSizes(minSupport, cap, nActive)

        val dims = Array(d) { bins(attributes[it], k) }

        // cell of every row, and prefix sums of the active rows over the (k+1)^d table (index b+1 per dim)
        val cellStride = IntArray(d) { i -> k.toDouble().pow(i).toInt() }
        val tableStride = IntArray(d) { i -> (k + 1).toDouble().pow(i).toInt() }
        val tableSize = (k + 1).toDouble().pow(d).toInt()
        val cellCount = IntArray(tableSize)
        val cellPos = IntArray(tableSize)
        // from pass 2 on, also all rows per cell: a box holding removed rows is not a candidate
        val cellAll: IntArray? = if (pass > 1) IntArray(tableSize) else null
        val rowCell = IntArray(n)
        for (r in 0 until n) {
            var cell = 0
            var t = 0
            for (i in 0 until d) {
                val b = dims[i].rowBin[r]
                cell += b * cellStride[i]
                t += (b + 1) * tableStride[i]
            }
            rowCell[r] = cell
            if (cellAll != null) cellAll[t]++
            if (active[r]) {
                cellCount[t]++
                if (labels[r] == 1) cellPos[t]++
            }
        }
        for (i in 0 until d) {
            val s = tableStride[i]
            for (t in 0 until tableSize) {
                if ((t / s) % (k + 1) >= 1) {
                    cellCount[t] += cellCount[t - s]
                    cellPos[t] += cellPos[t - s]
                    if (cellAll != null) cellAll[t] += cellAll[t - s]
                }
            }
        }

        // intervals per dim: (lo, hi) with hi - lo + 1 <= maxLen
        val intervals = ArrayList<IntArray>()
        for (lo in 0 until k) for (hi in lo until minOf(k, lo + maxLen)) intervals += intArrayOf(lo, hi)
        val nInt = intervals.size

        // corners over the first d-1 dims; inclusion-exclusion sign = (-1)^(dims taken at the lower bound)
        val outer = d - 1
        val nCorners = 1 shl outer
        val cornerSign = IntArray(nCorners) { c -> if ((outer - Integer.bitCount(c)) % 2 == 0) 1 else -1 }

        val buckets = SupportBuckets(cap, slots = 2 * d)
        var evaluated = 0L
        val choice = IntArray(outer)
        val lastStride = tableStride[d - 1]
        val mCount = IntArray(k + 1)
        val mPos = IntArray(k + 1)
        val mAll = IntArray(k + 1)

        while (true) {
            // marginal over the last dim for the chosen outer intervals: m[b] = active rows in bin b of the last dim
            java.util.Arrays.fill(mCount, 0)
            java.util.Arrays.fill(mPos, 0)
            if (cellAll != null) java.util.Arrays.fill(mAll, 0)
            for (c in 0 until nCorners) {
                var base = 0
                for (i in 0 until outer) {
                    val iv = intervals[choice[i]]
                    val coord = if ((c shr i) and 1 == 1) iv[1] + 1 else iv[0]
                    base += coord * tableStride[i]
                }
                val sign = cornerSign[c]
                for (b in 0 until k) {
                    val hiIdx = base + (b + 1) * lastStride
                    val loIdx = base + b * lastStride
                    mCount[b + 1] += sign * (cellCount[hiIdx] - cellCount[loIdx])
                    mPos[b + 1] += sign * (cellPos[hiIdx] - cellPos[loIdx])
                    if (cellAll != null) mAll[b + 1] += sign * (cellAll[hiIdx] - cellAll[loIdx])
                }
            }
            for (b in 1..k) {
                mCount[b] += mCount[b - 1]
                mPos[b] += mPos[b - 1]
                if (cellAll != null) mAll[b] += mAll[b - 1]
            }

            for (lo in 0 until k) {
                val hiMax = minOf(k - 1, lo + maxLen - 1)
                for (hi in lo..hiMax) {
                    val support = mCount[hi + 1] - mCount[lo]
                    if (support < minSupport || support > cap) continue
                    evaluated++
                    if (cellAll != null && mAll[hi + 1] - mAll[lo] != support) continue
                    val positives = mPos[hi + 1] - mPos[lo]
                    buckets.offer(support, positives) { payload, at ->
                        for (i in 0 until outer) {
                            val iv = intervals[choice[i]]
                            payload[at + 2 * i] = iv[0]
                            payload[at + 2 * i + 1] = iv[1]
                        }
                        payload[at + 2 * outer] = lo
                        payload[at + 2 * outer + 1] = hi
                    }
                }
            }

            // next combination of outer intervals (odometer)
            var i = 0
            while (i < outer) {
                choice[i]++
                if (choice[i] < nInt) break
                choice[i] = 0
                i++
            }
            if (i == outer) break
        }

        val kept = buckets.pareto(sizes)
        val rowCounts = IntArray(n)
        val cellScore = IntArray(cellStride[d - 1] * k)
        val boxes = ArrayList<ScanBox>(kept.size)
        val lo = IntArray(d)
        val hi = IntArray(d)
        for (support in kept) {
            for (i in 0 until d) {
                lo[i] = buckets.payload(support, 2 * i)
                hi[i] = buckets.payload(support, 2 * i + 1)
            }
            // value bounds: first non-empty bin >= lo, last non-empty bin <= hi (the box has rows, so both exist)
            val loV = DoubleArray(d)
            val hiV = DoubleArray(d)
            val pLo = DoubleArray(d)
            val pHi = DoubleArray(d)
            for (i in 0 until d) {
                var b1 = lo[i]
                while (dims[i].firstPos[b1] < 0) b1++
                var b2 = hi[i]
                while (dims[i].lastPos[b2] < 0) b2--
                val first = dims[i].firstPos[b1]
                val last = dims[i].lastPos[b2]
                loV[i] = orders.valueAt(attributes[i], first)
                hiV[i] = orders.valueAt(attributes[i], last)
                pLo[i] = percentileOf(first)
                pHi[i] = percentileOf(last)
            }
            // mark the cells of the box
            val cur = lo.copyOf()
            while (true) {
                var cell = 0
                for (i in 0 until d) cell += cur[i] * cellStride[i]
                cellScore[cell]++
                var i = 0
                while (i < d) {
                    cur[i]++
                    if (cur[i] <= hi[i]) break
                    cur[i] = lo[i]
                    i++
                }
                if (i == d) break
            }
            boxes += ScanBox(
                attributes = attributes.toIntArray(),
                lo = loV, hi = hiV, pLo = pLo, pHi = pHi,
                support = support,
                positives = buckets.bestPositives(support),
                pass = pass
            )
        }
        for (r in 0 until n) rowCounts[r] = cellScore[rowCell[r]]
        return BoxSet(boxes, rowCounts, evaluated)
    }

    // ------------------------------------------------------------------ scores with the parent

    /** Per-row scores over the rule set given by `hp.rocComparison`, as topRange does for the cheap front. */
    private fun combinedScores(boxes: List<ScanBox>, boxCounts: IntArray, parent: ParentContext?): DoubleArray {
        if (parent == null || hp.rocComparison == RocComparisonMode.CHILD) {
            return DoubleArray(n) { boxCounts[it].toDouble() }
        }
        if (hp.rocComparison == RocComparisonMode.CHILD_PLUS_PARENT) {
            return DoubleArray(n) { (boxCounts[it] + parent.counts[it]).toDouble() }
        }
        // MERGE: Pareto set of parent rules and boxes together, by (support, confidence)
        val m = parent.size + boxes.size
        val support = IntArray(m) { i -> if (i < parent.size) parent.support[i] else boxes[i - parent.size].support }
        val conf = DoubleArray(m) { i -> if (i < parent.size) parent.confidence[i] else boxes[i - parent.size].confidence }
        val scores = DoubleArray(n)
        for (i in 0 until m) {
            var dominated = false
            for (j in 0 until m) {
                if (i == j) continue
                if (support[j] >= support[i] && conf[j] >= conf[i] && (support[j] > support[i] || conf[j] > conf[i])) {
                    dominated = true
                    break
                }
            }
            if (dominated) continue
            if (i < parent.size) {
                for (r in parent.rows[i]) scores[r] += 1.0
            } else {
                val covered = BooleanArray(n)
                markBox(boxes[i - parent.size], covered)
                for (r in 0 until n) if (covered[r]) scores[r] += 1.0
            }
        }
        return scores
    }

    // ------------------------------------------------------------------ seeds

    companion object {
        /**
         * Up to this many distinct values every group-aligned window of a depth-1 attribute is enumerated
         * exactly (at most ~2,000 windows); above it, the best window per size of `buildWindowSizes` is
         * searched, as the random-AUC baseline does, and snapped to whole groups.
         */
        const val GROUP_ENUMERATION_LIMIT = 64

        /**
         * A box as a genotype for the full evolution: one gene per attribute of [cfgAttributes] (the
         * rule's attribute order), with the box's bounds and percentiles. null when the box breaks a
         * gene constraint (e.g. wider than `hp.maxWidth`), so that no seed is silently repaired away.
         */
        fun seedGenotype(
            box: ScanBox,
            cfgAttributes: List<Int>,
            cfg: RuleInitConfig,
            indexPool: IndexPool
        ): Genotype<AttributeGene>? {
            val genes = ArrayList<AttributeGene>(cfgAttributes.size)
            for (idx in cfgAttributes) {
                val i = box.attributes.indexOf(idx)
                if (i < 0) return null
                val gene = AttributeGene(
                    attributeIndex = idx,
                    lowerBound = box.lo[i],
                    upperBound = box.hi[i],
                    min = cfg.bounds[idx][0],
                    max = cfg.bounds[idx][1],
                    pLeft = box.pLo[i],
                    pRight = box.pHi[i],
                    cfg = cfg
                )
                if (!gene.isValid()) return null
                genes += gene
            }
            val chromosome = RuleSideChromosome(ISeq.of(genes), cfg, indexPool)
            if (!chromosome.isValid()) return null
            return Genotype.of(chromosome)
        }

        /**
         * At most [limit] boxes, evenly spread over the list (which is in support order per pass),
         * so that a long Pareto staircase does not crowd the parent seeds out of the population.
         */
        fun thinSeeds(boxes: List<ScanBox>, limit: Int): List<ScanBox> {
            if (limit <= 0) return emptyList()
            if (boxes.size <= limit) return boxes
            return List(limit) { i -> boxes[(i.toLong() * boxes.size / limit).toInt()] }
        }
    }
}

/**
 * Best positives per integer support, with a payload of [slots] ints per support (the box), and the
 * (support, confidence) Pareto sweep over them: one box per support value is enough, since boxes of
 * equal support with fewer positives are dominated.
 */
internal class SupportBuckets(private val maxSupport: Int, @PublishedApi internal val slots: Int) {
    @PublishedApi internal val best = IntArray(maxSupport + 1) { -1 }
    @PublishedApi internal val store = IntArray((maxSupport + 1) * slots)

    inline fun offer(support: Int, positives: Int, write: (IntArray, Int) -> Unit) {
        if (positives > best[support]) {
            best[support] = positives
            write(store, support * slots)
        }
    }

    fun bestPositives(support: Int): Int = best[support]

    fun payload(support: Int, slot: Int): Int = store[support * slots + slot]

    /**
     * Supports of the non-dominated boxes, descending, same dominance as `paretoFilterWindows`.
     * Before the sweep the supports are thinned to one per bin of [sizes] (the window sizes of the
     * random-AUC baseline, ascending): the bin (previous size, size] keeps its box of highest
     * confidence. With one size per support value the thinning changes nothing.
     */
    fun pareto(sizes: List<Int>): IntArray {
        val representatives = ArrayList<Int>()
        var prev = 0
        for (cut in sizes) {
            val hi = minOf(cut, maxSupport)
            var bestS = -1
            var bestConf = -1.0
            for (s in (prev + 1)..hi) {
                val p = best[s]
                if (p < 0) continue
                val conf = p.toDouble() / s
                if (conf > bestConf) { bestConf = conf; bestS = s }
            }
            if (bestS > 0) representatives += bestS
            prev = hi
            if (prev >= maxSupport) break
        }
        // supports above the last size (none when sizes end at the cap)
        for (s in (prev + 1)..maxSupport) if (best[s] >= 0) representatives += s

        val kept = ArrayList<Int>()
        var bestConf = -1.0
        for (s in representatives.sortedDescending()) {
            val conf = best[s].toDouble() / s
            if (conf > bestConf) {
                kept += s
                bestConf = conf
            }
        }
        return kept.toIntArray()
    }
}
