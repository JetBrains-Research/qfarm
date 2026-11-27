package org.jetbrains.bio.qfarm.logger

import java.util.concurrent.atomic.AtomicInteger

/**
 * One-line console progress bar, redrawn in place with a carriage return as units finish:
 *
 *     cheap X3+X1   [##############----------------]  2541/5089
 *
 * Thread-safe: [step] may be called from any thread. Redraws only when the drawn bar changes
 * (at most [width] + a few times per bar), so it costs nothing next to the work it tracks.
 * [finish] ends the line with the elapsed time.
 */
class ProgressBar(
    private val label: String,
    private val total: Int,
    private val width: Int = 30
) {
    private val done = AtomicInteger(0)
    private val startNanos = System.nanoTime()
    @Volatile private var lastFilled = -1

    init {
        if (total > 0) draw(0)
    }

    fun step() {
        val d = done.incrementAndGet()
        if (total <= 0) return
        val filled = (d.toLong() * width / total).toInt()
        if (filled != lastFilled || d == total) draw(d)
    }

    @Synchronized
    private fun draw(d: Int) {
        val filled = (d.toLong() * width / total).toInt()
        if (filled == lastFilled && d != total) return
        lastFilled = filled
        val bar = "#".repeat(filled) + "-".repeat(width - filled)
        val w = total.toString().length
        print("\r  $label  [$bar]  ${d.toString().padStart(w)}/$total")
        System.out.flush()
    }

    @Synchronized
    fun finish() {
        if (total <= 0) return
        draw(total)
        val seconds = (System.nanoTime() - startNanos) / 1e9
        println("  ${"%.2f".format(seconds)} s")
    }
}
