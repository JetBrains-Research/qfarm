package org.jetbrains.bio.qfarm.statistics.delong

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
open class DeLongBenchmark {

    @Param("1000", "10000", "100000")
    var size: Int = 1000

    private lateinit var labels: IntArray
    private lateinit var scores1: DoubleArray
    private lateinit var scores2: DoubleArray

    @Setup
    fun setup() {
        val random = Random(42)
        labels = IntArray(size) { if (random.nextDouble() > 0.5) 1 else 0 }
        scores1 = DoubleArray(size) { random.nextDouble() }
        scores2 = DoubleArray(size) { random.nextDouble() }
    }

    @Benchmark
    fun testDeLong(): DeLongResult {
        return DeLong.compare(labels, scores1, scores2)
    }
}
