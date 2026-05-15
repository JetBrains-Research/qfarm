package org.jetbrains.bio.qfarm.util

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Pareto
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene


// ---------------------- Pareto front (content-based equality) ----------------------

fun paretoFrontOf(
    population: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
): List<Phenotype<AttributeGene, Vec<DoubleArray>>> {
    val fits: ISeq<Vec<DoubleArray>> = population.map { it.fitness() }
    val frontFits: ISeq<Vec<DoubleArray>> = Pareto.front(fits)

    // Build a content-based key for Vec<DoubleArray> (avoids reference equality pitfalls)
    fun key(v: Vec<DoubleArray>): String = v.data().joinToString("\u0001") { it.toString() }
    val frontKeys = frontFits.asList().map(::key).toHashSet()

    return population.stream()
        .filter { pt -> key(pt.fitness()) in frontKeys }
        .toList()
}
