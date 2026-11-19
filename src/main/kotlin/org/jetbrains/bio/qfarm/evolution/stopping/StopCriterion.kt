package org.jetbrains.bio.qfarm.evolution.stopping

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene

typealias RulePhenotype = Phenotype<AttributeGene, Vec<DoubleArray>>

/** One generation's verdict of a [StopCriterion]. */
data class StopObservation(
    /** Criterion-specific value for this generation, written to the calibration log. */
    val signal: Double,
    val shouldStop: Boolean
)

/**
 * Online stop criterion for one evolution. Implementations keep state across
 * generations, so every evolution needs a fresh instance.
 */
interface StopCriterion {
    /** Short name written to the calibration log. */
    val name: String

    /** What [StopObservation.signal] measures, written to the calibration log. */
    val signalName: String

    /**
     * Called once per generation.
     *
     * @param survivors the survivor set the engine kept in this generation
     * @param population the evaluated population of this generation
     */
    fun update(
        generation: Long,
        survivors: ISeq<RulePhenotype>,
        population: ISeq<RulePhenotype>
    ): StopObservation
}
