package org.jetbrains.bio.qfarm.statistics.delong
import kotlinx.serialization.Serializable

@Serializable
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