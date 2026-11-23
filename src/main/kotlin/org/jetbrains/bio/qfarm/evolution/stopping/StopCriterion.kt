package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene

typealias RulePhenotype = Phenotype<AttributeGene, Vec<DoubleArray>>

/** One generation's verdict of a [StopCriterion]. */
data class StopObservation(
    /** The quantity the criterion thresholds, written to the calibration log (NaN while undefined). */
    val signal: Double,
    /** The raw per-generation measurement the signal is built from (NaN while undefined). */
    val indicator: Double,
    val shouldStop: Boolean
)

/**
 * Online stop criterion for one evolution. Implementations keep state across
 * generations, so every evolution needs a fresh instance.
 */
interface StopCriterion {
    /** Short name written to the calibration log. */
    val name: String

    /** What [StopObservation.signal] measures. */
    val signalName: String

    /** What [StopObservation.indicator] measures. */
    val indicatorName: String

    /** Called once per generation with that generation's evaluated population. */
    fun update(generation: Long, population: ISeq<RulePhenotype>): StopObservation
}
