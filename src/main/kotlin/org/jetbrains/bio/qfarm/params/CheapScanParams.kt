package org.jetbrains.bio.qfarm.params

import kotlinx.serialization.Serializable

/**
 * Settings of the cheap phase's exact box scan, which replaces the cheap evolution when [enabled].
 * Not exposed on the CLI: change the defaults below. For an A/B against the cheap evolution the
 * system property `-Dqfarm.cheapScan=false` switches the scan off without touching the code.
 *
 * The scan ranks every candidate addition `prefix + attr` by the AUC of the best boxes over those
 * attributes (see `evaluation/scan/CheapScan.kt`): exact windows along the attribute at depth 1,
 * all boxes of a percentile grid at depth >= 2. It also looks for further modes (positives the
 * first boxes leave unexplained) and hands all boxes to the full evolution as seeds.
 */
@Serializable
data class CheapScanParams(

    /** true: cheap phase = box scan. false: the cheap evolution of before (popSizeCheap, maxGenCheap). */
    val enabled: Boolean = System.getProperty("qfarm.cheapScan")?.toBooleanStrictOrNull() ?: true,

    /** Upper bound of percentile bins per attribute of the grid (depth >= 2). */
    val maxBins: Int = 20,

    /**
     * Rows a grid cell should hold on average: bins per attribute = (rows / minRowsPerCell)^(1/d),
     * capped by [maxBins] and never below 2.
     */
    val minRowsPerCell: Int = 8,

    /**
     * Maximum number of box sets (modes) per candidate: after the first set, the positives it covers
     * are masked and the scan is repeated; a further set is kept only if its best box is significant.
     * 1 = no search for further modes.
     */
    val maxModes: Int = 2,

    /**
     * Significance level of a further mode: hypergeometric tail of its best box, Bonferroni-corrected
     * over the boxes evaluated. null = `hp.alphaThreshold`.
     */
    val modeAlpha: Double? = null,
) {
    init {
        require(maxBins >= 2) { "maxBins must be >= 2, was $maxBins" }
        require(minRowsPerCell >= 1) { "minRowsPerCell must be >= 1, was $minRowsPerCell" }
        require(maxModes >= 1) { "maxModes must be >= 1, was $maxModes" }
        require(modeAlpha == null || modeAlpha in 0.0..1.0) { "modeAlpha must be in [0, 1], was $modeAlpha" }
    }

    fun alpha(): Double = modeAlpha ?: hp.alphaThreshold
}

/** Active cheap-scan settings. */
val cheapScanParams = CheapScanParams()
