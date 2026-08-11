package org.jetbrains.bio.qfarm.util

import io.jenetics.Phenotype
import io.jenetics.ext.moea.Pareto
import io.jenetics.ext.moea.Vec
import io.jenetics.util.ISeq
import org.jetbrains.bio.qfarm.core.AttributeGene
import org.jetbrains.bio.qfarm.evolution.ScoredFront


// ---------------------- Pareto front (content-based equality) ----------------------

fun paretoFrontOf(
    population: ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>>
): ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> {

    val uniqueByGenotype =
        population.asList()
            .distinctBy { it.genotype() }

    return Pareto.front(
        ISeq.of(uniqueByGenotype)
    ) { a, b ->
        a.fitness().compareTo(b.fitness())
    }
}

fun combinedParetoFront(
    parentScoredFront: ScoredFront?,
    childScoredFront: ScoredFront
): ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> {

    val parentFront = parentScoredFront?.front

    if (parentFront == null || parentFront.isEmpty) {
        return paretoFrontOf(childScoredFront.front)
    }

    val union =
        parentFront.append(childScoredFront.front)

    return paretoFrontOf(union)
}
