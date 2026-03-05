package org.jetbrains.bio.qfarm.statistics.delong

data class DeLongResult(
    val auc1: Double,
    val auc2: Double,
    val variance1: Double,
    val variance2: Double,
    val covariance: Double,
    val zScore: Double,
    val pTwoSided: Double,
    val pOneSided: Double
)