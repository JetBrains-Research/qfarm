package org.jetbrains.bio.qfarm

import io.jenetics.Genotype
import io.jenetics.Optimize
import io.jenetics.Phenotype
import io.jenetics.Selector
import io.jenetics.ext.moea.NSGA2Selector
import io.jenetics.ext.moea.Vec
import io.jenetics.util.Factory
import io.jenetics.util.ISeq
import io.jenetics.util.Seq

class RandomNSGA2(
    val genotypeFactory: Factory<Genotype<AttributeGene>>,
    val fitnessFunction: (Genotype<AttributeGene>) -> Vec<DoubleArray>
): Selector<AttributeGene, Vec<DoubleArray>> {
    companion object {
                var currentGeneration: Int = 1
    }
    val nsga2 = NSGA2Selector.ofVec<AttributeGene, DoubleArray, Vec<DoubleArray>>()

    override fun select(
        population: Seq<Phenotype<AttributeGene, Vec<DoubleArray>>>,
        count: Int,
        opt: Optimize
    ): ISeq<Phenotype<AttributeGene, Vec<DoubleArray>>> {

        val numRandom = (count * 0.5).toInt()
        val numNSGA = count - numRandom

        val randomGenotypes = List(numRandom) { genotypeFactory.newInstance() }

        val randomPhenotypes = randomGenotypes.map { g ->
            Phenotype.of(g, currentGeneration.toLong(), fitnessFunction(g))
        }

        val nsgaSurvivors = nsga2.select(
            ISeq.of(population),
            numNSGA,
            Optimize.MAXIMUM
        )

        val combined = nsgaSurvivors + randomPhenotypes
//        println("Selected ${nsgaSurvivors.size()} from survivors and ${randomPhenotypes.size} random new individuals")

        return ISeq.of(combined)
    }
}