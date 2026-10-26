package org.jetbrains.bio.qfarm.analyze

import kotlin.math.sqrt

/**
 * Agglomerative clustering in the scipy.cluster.hierarchy conventions:
 * row i of [merges] joins clusters left/right (ids < n are points, n + i is the cluster of row i).
 */
class Linkage(val n: Int, val left: IntArray, val right: IntArray, val height: DoubleArray, val size: IntArray) {

    /** Leaf order of the dendrogram (scipy leaves_list): pre-order, left child first. */
    fun leaves(): IntArray {
        if (n == 1) return intArrayOf(0)
        val out = ArrayList<Int>(n)
        val stack = ArrayDeque<Int>().apply { addLast(2 * n - 2) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (c < n) out += c else {
                stack.addLast(right[c - n])
                stack.addLast(left[c - n])
            }
        }
        return out.toIntArray()
    }

    /**
     * scipy fcluster(Z, k, "maxclust"): the lowest height cut giving at most k clusters.
     * Ids follow scipy's traversal: a merged cluster is numbered when its subtree is entered,
     * a singleton leaf after its sibling subtree has been explored.
     */
    fun cut(k: Int): IntArray {
        if (k >= n) return IntArray(n) { it + 1 }      // all singletons, numbered by point (as scipy)
        val cutoff = height.sorted().first { t -> n - height.count { it <= t } <= k }
        val labels = IntArray(n)
        var next = 0
        fun assign(root: Int, id: Int) {
            val stack = ArrayDeque<Int>().apply { addLast(root) }
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                if (c < n) labels[c] = id else {
                    stack.addLast(left[c - n])
                    stack.addLast(right[c - n])
                }
            }
        }
        // iterative post-order over internal nodes above the cutoff; negative entries = "children done"
        val stack = ArrayDeque<Int>().apply { addLast(2 * n - 2) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (c < 0) {
                val node = -c - 1
                for (ch in intArrayOf(left[node - n], right[node - n])) if (ch < n) labels[ch] = ++next
                continue
            }
            if (height[c - n] <= cutoff) {
                assign(c, ++next)
                continue
            }
            stack.addLast(-c - 1)
            if (right[c - n] >= n) stack.addLast(right[c - n])
            if (left[c - n] >= n) stack.addLast(left[c - n])
        }
        return labels
    }

    /** Cophenetic distance for every pair (i < j), in condensed order. */
    fun cophenetic(): DoubleArray {
        val members = ArrayList<IntArray>(2 * n - 1)
        for (i in 0 until n) members += intArrayOf(i)
        val c = Array(n) { DoubleArray(n) }
        for (i in 0 until n - 1) {
            val a = members[left[i]]
            val b = members[right[i]]
            for (x in a) for (y in b) {
                c[x][y] = height[i]
                c[y][x] = height[i]
            }
            members += a + b
        }
        return condensed(c)
    }

    /** Segments of a scipy-style dendrogram: leaf k sits at 5 + 10 * (position of k in leaves()). */
    fun dendrogramSegments(): List<DoubleArray> {
        val pos = DoubleArray(2 * n - 1)
        val h = DoubleArray(2 * n - 1)
        leaves().forEachIndexed { p, leaf -> pos[leaf] = 5.0 + 10.0 * p }
        val segs = ArrayList<DoubleArray>()          // (h1, p1, h2, p2)
        for (i in 0 until n - 1) {
            val l = left[i]
            val r = right[i]
            pos[n + i] = (pos[l] + pos[r]) / 2
            h[n + i] = height[i]
            segs += doubleArrayOf(h[l], pos[l], height[i], pos[l])
            segs += doubleArrayOf(h[r], pos[r], height[i], pos[r])
            segs += doubleArrayOf(height[i], pos[l], height[i], pos[r])
        }
        return segs
    }
}

fun condensed(d: Array<DoubleArray>): DoubleArray {
    val n = d.size
    val out = DoubleArray(n * (n - 1) / 2)
    var k = 0
    for (i in 0 until n) for (j in i + 1 until n) out[k++] = d[i][j]
    return out
}

/**
 * Port of scipy's nearest-neighbour-chain linkage ("ward" or "average") on a full distance matrix,
 * including the final stable sort by height and the union-find relabelling.
 */
fun linkage(d: Array<DoubleArray>, method: String): Linkage {
    val n = d.size
    require(method == "ward" || method == "average") { "Unsupported linkage '$method' (use ward or average)" }
    val dist = Array(n) { d[it].copyOf() }
    val size = IntArray(n) { 1 }
    val rx = IntArray(n - 1)
    val ry = IntArray(n - 1)
    val rh = DoubleArray(n - 1)
    val chain = IntArray(n)
    var chainLen = 0

    for (k in 0 until n - 1) {
        if (chainLen == 0) {
            chain[chainLen++] = (0 until n).first { size[it] > 0 }
        }
        var x: Int
        var y = -1
        var currentMin: Double
        while (true) {
            x = chain[chainLen - 1]
            if (chainLen > 1) {
                y = chain[chainLen - 2]
                currentMin = dist[x][y]
            } else currentMin = Double.POSITIVE_INFINITY
            for (i in 0 until n) {
                if (size[i] == 0 || i == x) continue
                if (dist[x][i] < currentMin) {
                    currentMin = dist[x][i]
                    y = i
                }
            }
            if (chainLen > 1 && y == chain[chainLen - 2]) break
            chain[chainLen++] = y
        }
        chainLen -= 2
        if (x > y) x = y.also { y = x }
        val nx = size[x]
        val ny = size[y]
        rx[k] = x
        ry[k] = y
        rh[k] = currentMin
        size[x] = 0
        size[y] = nx + ny
        for (i in 0 until n) {
            val ni = size[i]
            if (ni == 0 || i == y) continue
            val v = if (method == "average") {
                (nx * dist[i][x] + ny * dist[i][y]) / (nx + ny)
            } else {
                val t = 1.0 / (nx + ny + ni)
                sqrt((ni + nx) * t * dist[i][x] * dist[i][x] + (ni + ny) * t * dist[i][y] * dist[i][y] -
                        ni * t * currentMin * currentMin)
            }
            dist[i][y] = v
            dist[y][i] = v
        }
    }

    // stable sort by height, then relabel with union-find (scipy label())
    val order = (0 until n - 1).sortedBy { rh[it] }
    val parent = IntArray(2 * n - 1) { it }
    val sz = IntArray(2 * n - 1) { 1 }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        return r
    }
    val left = IntArray(n - 1)
    val right = IntArray(n - 1)
    val height = DoubleArray(n - 1)
    val csize = IntArray(n - 1)
    for ((i, o) in order.withIndex()) {
        val a = find(rx[o])
        val b = find(ry[o])
        left[i] = minOf(a, b)
        right[i] = maxOf(a, b)
        height[i] = rh[o]
        parent[a] = n + i
        parent[b] = n + i
        sz[n + i] = sz[a] + sz[b]
        csize[i] = sz[n + i]
    }
    return Linkage(n, left, right, height, csize)
}
